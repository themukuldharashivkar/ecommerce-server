package com.bazaarly.payment;

import com.bazaarly.exception.OrderNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.OrderItem;
import com.bazaarly.modal.User;
import com.bazaarly.notification.OrderNotificationStore;
import com.bazaarly.notification.OrderPaidEvent;
import com.bazaarly.repository.CartItemRepository;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.response.PaymentLinkResponse;
import com.bazaarly.service.OrderService;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;
import com.razorpay.RazorpayException;

/** Creates payment links for orders and confirms payments server-side with the provider. */
@Service
public class PaymentService {

	static final String CAPTURED = "captured";
	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final PaymentGateway paymentGateway;
	private final OrderService orderService;
	private final OrderRepository orderRepository;
	private final OrderNotificationStore notificationStore;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate transaction;
	private final String frontendUrl;
	private final CartItemRepository cartItemRepository;
	private final Duration reservationTtl;
	private final Clock clock;

	/** Razorpay rejects links that expire less than 15 minutes ahead; keep a minute of margin. */
	static final Duration MIN_LINK_LIFETIME = Duration.ofMinutes(16);

	public PaymentService(PaymentGateway paymentGateway, OrderService orderService, OrderRepository orderRepository,
			OrderNotificationStore notificationStore, ApplicationEventPublisher events, TransactionTemplate transaction,
			@Value("${app.frontend-url}") String frontendUrl, CartItemRepository cartItemRepository,
			@Value("${app.orders.reservation-ttl}") Duration reservationTtl, Clock clock) {
		this.cartItemRepository = cartItemRepository;
		this.reservationTtl = reservationTtl;
		this.clock = clock;
		this.paymentGateway = paymentGateway;
		this.orderService = orderService;
		this.orderRepository = orderRepository;
		this.notificationStore = notificationStore;
		this.events = events;
		this.transaction = transaction;
		this.frontendUrl = frontendUrl;
	}

	public PaymentLinkResponse createPaymentLink(Long orderId, String callerEmail) throws OrderException, RazorpayException {
		Order order = findOwnOrder(orderId, callerEmail);
		if (isPaid(order)) {
			throw new OrderException("order " + orderId + " is already paid");
		}
		if (order.getOrderStatus() == OrderStatus.CANCELLED) {
			throw new OrderException("This order was cancelled or has expired. Please place it again.");
		}
		// the link must expire when the stock reservation does, and Razorpay needs >= 15 minutes
		Instant expiresAt = order.getCreatedAt().atZone(clock.getZone()).toInstant().plus(reservationTtl);
		if (expiresAt.isBefore(clock.instant().plus(MIN_LINK_LIFETIME))) {
			throw new OrderException("This order is about to expire. Please place it again.");
		}
		User user = order.getUser();
		// charge what the shopper sees at checkout: the total after discounts, in paise
		long payable = payableInPaise(order);

		// one payable link per order: reuse it, so a payment can always be matched to the stored link
		String existingLinkId = order.getPaymentDetails().getRazorpayPaymentLinkId();
		if (existingLinkId != null) {
			PaymentGateway.PaymentLinkState existing = paymentGateway.fetchPaymentLink(existingLinkId);
			if (existing != null && existing.isPayable() && existing.shortUrl() != null) {
				return new PaymentLinkResponse(existing.shortUrl(), existing.id());
			}
		}

		PaymentGateway.CreatedPaymentLink link = paymentGateway.createPaymentLink(new PaymentGateway.PaymentLinkRequest(
				payable,
				user.getFirstName() + " " + user.getLastName(),
				user.getMobile(),
				user.getEmail(),
				frontendUrl + "/payment/" + orderId,
				expiresAt.getEpochSecond()));

		order.setOrderId(link.providerOrderId());
		order.getPaymentDetails().setRazorpayPaymentLinkId(link.id());
		orderRepository.save(order);

		return new PaymentLinkResponse(link.shortUrl(), link.id());
	}

	/**
	 * Called when the shopper returns from Razorpay. Asks Razorpay for the real payment and only marks the
	 * order paid if the payment was captured, belongs to this order's Razorpay order and is for the full amount.
	 *
	 * Idempotent: retries and concurrent calls for the same order mark it paid once and create exactly one
	 * order-confirmation notification. The confirmation email itself is sent in the background after commit.
	 */
	public ApiResponse confirmPayment(String paymentId, Long orderId, String callerEmail)
			throws OrderException, RazorpayException {
		Order order = findOwnOrder(orderId, callerEmail);
		if (isPaid(order)) {
			log.info("Order {} already paid; confirmation for payment {} is a no-op", orderId, paymentId);
			return placed();
		}

		PaymentGateway.ProviderPayment payment;
		try {
			payment = paymentGateway.fetchPayment(paymentId);
		} catch (Exception e) {
			log.warn("Could not fetch payment {} for order {}: {}", paymentId, orderId, e.getMessage());
			throw new RazorpayException(e.getMessage());
		}

		if (!CAPTURED.equals(payment.status())) {
			log.info("Payment {} for order {} not captured (status {})", paymentId, orderId, payment.status());
			return new ApiResponse("payment not completed yet", false);
		}
		if (!belongsToOrder(order, paymentId, payment)) {
			log.warn("Payment {} belongs to provider order {}, not to order {} ({})", paymentId,
					payment.providerOrderId(), orderId, order.getOrderId());
			throw new OrderException("payment " + paymentId + " does not belong to order " + orderId);
		}
		if (payment.amountInPaise() != payableInPaise(order)) {
			log.warn("Payment {} amount {} does not match order {} amount {}", paymentId, payment.amountInPaise(),
					orderId, payableInPaise(order));
			throw new OrderException("payment " + paymentId + " amount does not match order " + orderId);
		}

		if (order.getOrderStatus() == OrderStatus.CANCELLED) {
			return expiredButPaid(order, paymentId);
		}
		Boolean placedNow = transaction.execute(status -> markPaid(orderId, paymentId));
		if (Boolean.FALSE.equals(placedNow)) {
			return expiredButPaid(orderService.findOrderById(orderId), paymentId);
		}
		return placed();
	}

	/**
	 * An expired order has released its stock, so a payment that arrives afterwards cannot place it.
	 * The payment is refunded automatically (idempotent, so a repeated confirmation does not refund twice).
	 */
	private ApiResponse expiredButPaid(Order order, String paymentId) throws OrderException {
		try {
			PaymentGateway.Refund refund = paymentGateway.refundInFull(paymentId, payableInPaise(order),
					"Order " + order.getId() + " expired before payment");
			orderService.recordRefundOnCancelledOrder(order.getId(), paymentId, refund.id());
			log.warn("Payment {} arrived after order {} expired; refunded ({})", paymentId, order.getId(), refund.id());
			throw new OrderException("This order expired before the payment completed. Your payment has been refunded.");
		} catch (RazorpayException e) {
			log.error("Payment {} for expired order {} could not be refunded automatically: {}", paymentId, order.getId(),
					e.getMessage());
			throw new OrderException("This order expired before the payment completed and the automatic refund failed. "
					+ "Please contact support.");
		}
	}

	/**
	 * Admin cancellation. A paid order is refunded in full first (outside the database transaction, it is an
	 * external call), then cancelled with its stock given back. If the refund fails nothing changes and the
	 * admin can retry; a retry never refunds twice (the gateway checks what was already refunded).
	 */
	public Order cancelOrder(Long orderId) throws OrderException, RazorpayException {
		Order order = orderService.findOrderById(orderId);
		if (order.getOrderStatus() == OrderStatus.CANCELLED) {
			return order;
		}
		if (order.getOrderStatus() == OrderStatus.SHIPPED || order.getOrderStatus() == OrderStatus.DELIVERED) {
			throw new OrderException("Order " + orderId + " has already shipped and cannot be cancelled");
		}
		String refundId = null;
		if (isPaid(order)) {
			PaymentGateway.Refund refund = paymentGateway.refundInFull(order.getPaymentDetails().getPaymentId(),
					payableInPaise(order), "Order " + orderId + " cancelled");
			refundId = refund.id();
			log.info("Order {} refunded ({}) before cancelling", orderId, refundId);
		}
		return orderService.cancelOrder(orderId, refundId);
	}

	/**
	 * Runs in one transaction with the order row locked; the outbox row, the event and the cart clean-up
	 * commit together with it. Returns false if the order was cancelled or expired in the meantime.
	 */
	private boolean markPaid(Long orderId, String paymentId) {
		Order locked = orderRepository.findByIdForUpdate(orderId).orElseThrow();
		if (isPaid(locked)) {
			log.info("Order {} was marked paid by a concurrent confirmation", orderId);
			return true;
		}
		if (locked.getOrderStatus() == OrderStatus.CANCELLED) {
			return false;
		}
		locked.getPaymentDetails().setPaymentId(paymentId);
		locked.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		locked.setOrderStatus(OrderStatus.PLACED);
		orderRepository.save(locked);

		// the paid items leave the cart; anything added after the order was placed stays
		for (OrderItem item : locked.getOrderItems()) {
			cartItemRepository.deleteLine(locked.getUser().getId(), item.getProduct().getId(), item.getSize());
		}

		if (notificationStore.createIfAbsent(orderId)) {
			events.publishEvent(new OrderPaidEvent(orderId));
		}
		log.info("Payment {} captured, order {} placed", paymentId, orderId);
		return true;
	}

	/**
	 * The payment must have been made through this order's payment link. Razorpay creates the order behind a
	 * payment link only when it is paid, so the link is asked which payments it received.
	 */
	private boolean belongsToOrder(Order order, String paymentId, PaymentGateway.ProviderPayment payment)
			throws RazorpayException {
		if (order.getOrderId() != null && order.getOrderId().equals(payment.providerOrderId())) {
			return true;
		}
		String linkId = order.getPaymentDetails().getRazorpayPaymentLinkId();
		if (linkId == null) {
			return false;
		}
		PaymentGateway.PaymentLinkState link = paymentGateway.fetchPaymentLink(linkId);
		if (link == null) {
			return false;
		}
		return link.paymentIds().contains(paymentId)
				|| (link.providerOrderId() != null && link.providerOrderId().equals(payment.providerOrderId()));
	}

	/** Unknown orders and other users' orders look the same, so ids cannot be probed. */
	private Order findOwnOrder(Long orderId, String callerEmail) throws OrderException {
		Order order = orderService.findOrderById(orderId);
		if (callerEmail == null || order.getUser() == null || !callerEmail.equals(order.getUser().getEmail())) {
			throw new OrderNotFoundException("order not exist with id " + orderId);
		}
		return order;
	}

	private static boolean isPaid(Order order) {
		return order.getPaymentDetails() != null && order.getPaymentDetails().getStatus() == PaymentStatus.COMPLETED;
	}

	private static long payableInPaise(Order order) throws OrderException {
		Integer payable = order.getTotalDiscountedPrice();
		if (payable == null || payable <= 0) {
			throw new OrderException("order " + order.getId() + " has nothing to pay");
		}
		return payable * 100L;
	}

	private static ApiResponse placed() {
		return new ApiResponse("your order got placed", true);
	}
}
