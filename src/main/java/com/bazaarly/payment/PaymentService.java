package com.bazaarly.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;
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
	private final String frontendUrl;

	public PaymentService(PaymentGateway paymentGateway, OrderService orderService, OrderRepository orderRepository,
			@Value("${app.frontend-url}") String frontendUrl) {
		this.paymentGateway = paymentGateway;
		this.orderService = orderService;
		this.orderRepository = orderRepository;
		this.frontendUrl = frontendUrl;
	}

	public PaymentLinkResponse createPaymentLink(Long orderId) throws OrderException, RazorpayException {
		Order order = orderService.findOrderById(orderId);
		User user = order.getUser();

		PaymentGateway.CreatedPaymentLink link = paymentGateway.createPaymentLink(new PaymentGateway.PaymentLinkRequest(
				order.getTotalPrice() * 100,
				user.getFirstName() + " " + user.getLastName(),
				user.getMobile(),
				user.getEmail(),
				frontendUrl + "/payment/" + orderId));

		order.setOrderId(link.providerOrderId());
		orderRepository.save(order);

		return new PaymentLinkResponse(link.shortUrl(), link.id());
	}

	/**
	 * Called when the shopper returns from Razorpay. Asks Razorpay for the real payment status
	 * and only marks the order paid when the payment was captured.
	 */
	public ApiResponse confirmPayment(String paymentId, Long orderId) throws OrderException, RazorpayException {
		Order order = orderService.findOrderById(orderId);

		String status;
		try {
			status = paymentGateway.fetchPaymentStatus(paymentId);
		} catch (Exception e) {
			log.warn("Could not fetch payment {} for order {}: {}", paymentId, orderId, e.getMessage());
			throw new RazorpayException(e.getMessage());
		}

		if (CAPTURED.equals(status)) {
			order.getPaymentDetails().setPaymentId(paymentId);
			order.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
			order.setOrderStatus(OrderStatus.PLACED);
			orderRepository.save(order);
			log.info("Payment {} captured, order {} placed", paymentId, orderId);
		} else {
			log.info("Payment {} for order {} not captured (status {})", paymentId, orderId, status);
		}
		return new ApiResponse("your order got placed", true);
	}
}
