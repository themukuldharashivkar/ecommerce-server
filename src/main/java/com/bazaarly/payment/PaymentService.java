package com.bazaarly.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;
import com.bazaarly.notification.OrderNotificationStore;
import com.bazaarly.notification.OrderPaidEvent;
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

	public PaymentService(PaymentGateway paymentGateway, OrderService orderService, OrderRepository orderRepository,
			OrderNotificationStore notificationStore, ApplicationEventPublisher events, TransactionTemplate transaction,
			@Value("${app.frontend-url}") String frontendUrl) {
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
		User user = order.getUser();
		// charge what the shopper sees at checkout: the total after discounts, in paise
		long payable = payableInPaise(order);

		PaymentGateway.CreatedPaymentLink link = paymentGateway.createPaymentLink(new PaymentGateway.PaymentLinkRequest(
				payable,
				user.getFirstName() + " " + user.getLastName(),
				user.getMobile(),
				user.getEmail(),
				frontendUrl + "/payment/" + orderId));

		order.setOrderId(link.providerOrderId());
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
			return placed();
		}
		if (order.getOrderId() == null || !order.getOrderId().equals(payment.providerOrderId())) {
			log.warn("Payment {} belongs to provider order {}, not to order {} ({})", paymentId,
					payment.providerOrderId(), orderId, order.getOrderId());
			throw new OrderException("payment " + paymentId + " does not belong to order " + orderId);
		}
		if (payment.amountInPaise() != payableInPaise(order)) {
			log.warn("Payment {} amount {} does not match order {} amount {}", paymentId, payment.amountInPaise(),
					orderId, payableInPaise(order));
			throw new OrderException("payment " + paymentId + " amount does not match order " + orderId);
		}

		transaction.executeWithoutResult(status -> markPaid(orderId, paymentId));
		return placed();
	}

	/** Runs in one transaction with the order row locked; the outbox row and event commit together with it. */
	private void markPaid(Long orderId, String paymentId) {
		Order locked = orderRepository.findByIdForUpdate(orderId).orElseThrow();
		if (isPaid(locked)) {
			log.info("Order {} was marked paid by a concurrent confirmation", orderId);
			return;
		}
		locked.getPaymentDetails().setPaymentId(paymentId);
		locked.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		locked.setOrderStatus(OrderStatus.PLACED);
		orderRepository.save(locked);

		if (notificationStore.createIfAbsent(orderId)) {
			events.publishEvent(new OrderPaidEvent(orderId));
		}
		log.info("Payment {} captured, order {} placed", paymentId, orderId);
	}

	/** Unknown orders and other users' orders look the same, so ids cannot be probed. */
	private Order findOwnOrder(Long orderId, String callerEmail) throws OrderException {
		Order order = orderService.findOrderById(orderId);
		if (callerEmail == null || order.getUser() == null || !callerEmail.equals(order.getUser().getEmail())) {
			throw new OrderException("order not exist with id " + orderId);
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
