package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.payment.PaymentService;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.response.PaymentLinkResponse;
import com.bazaarly.service.OrderService;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;
import com.razorpay.RazorpayException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@Mock PaymentGateway gateway;
	@Mock OrderService orderService;
	@Mock OrderRepository orderRepository;

	PaymentService service;
	Order order;

	@BeforeEach
	void setUp() {
		service = new PaymentService(gateway, orderService, orderRepository, "https://shop.example");
		User user = new User();
		user.setFirstName("Asha");
		user.setLastName("Rao");
		user.setEmail("asha@example.com");
		user.setMobile("9876543210");
		order = new Order();
		order.setId(42L);
		order.setUser(user);
		order.setTotalPrice(1499);          // MRP
		order.setTotalDiscountedPrice(1199); // what the shopper pays
		order.setOrderStatus(OrderStatus.PENDING);
		order.getPaymentDetails().setStatus(PaymentStatus.PENDING);
	}

	@Test
	void createPaymentLinkSendsAmountInPaiseCustomerAndReturnUrl() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.createPaymentLink(any()))
				.thenReturn(new PaymentGateway.CreatedPaymentLink("plink_1", "https://rzp.io/x", "order_rzp_1"));

		PaymentLinkResponse response = service.createPaymentLink(42L);

		ArgumentCaptor<PaymentGateway.PaymentLinkRequest> sent = ArgumentCaptor.forClass(PaymentGateway.PaymentLinkRequest.class);
		verify(gateway).createPaymentLink(sent.capture());
		assertThat(sent.getValue().amountInPaise()).isEqualTo(119900L); // discounted total, not the 1499 MRP
		assertThat(sent.getValue().customerName()).isEqualTo("Asha Rao");
		assertThat(sent.getValue().customerEmail()).isEqualTo("asha@example.com");
		assertThat(sent.getValue().customerContact()).isEqualTo("9876543210");
		assertThat(sent.getValue().callbackUrl()).isEqualTo("https://shop.example/payment/42");

		assertThat(response.getPayment_link_url()).isEqualTo("https://rzp.io/x");
		assertThat(response.getPayment_link_id()).isEqualTo("plink_1");
		assertThat(order.getOrderId()).isEqualTo("order_rzp_1");
		verify(orderRepository).save(order);
	}

	@Test
	void orderWithNothingToPayNeverCallsTheGateway() throws Exception {
		order.setTotalDiscountedPrice(0);
		when(orderService.findOrderById(42L)).thenReturn(order);

		assertThatThrownBy(() -> service.createPaymentLink(42L))
				.isInstanceOf(OrderException.class)
				.hasMessage("order 42 has nothing to pay");
		verifyNoInteractions(gateway);
	}

	@Test
	void createPaymentLinkForAnUnknownOrderNeverCallsTheGateway() throws Exception {
		when(orderService.findOrderById(404L)).thenThrow(new OrderException("order not exist with id 404"));

		assertThatThrownBy(() -> service.createPaymentLink(404L)).isInstanceOf(OrderException.class);
		verifyNoInteractions(gateway);
	}

	@Test
	void gatewayFailureWhileCreatingALinkLeavesTheOrderUntouched() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.createPaymentLink(any())).thenThrow(new RazorpayException("bad key"));

		assertThatThrownBy(() -> service.createPaymentLink(42L)).isInstanceOf(RazorpayException.class);
		verify(orderRepository, never()).save(any());
		assertThat(order.getOrderId()).isNull();
	}

	@Test
	void capturedPaymentMarksTheOrderPlacedAndPaid() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPaymentStatus("pay_1")).thenReturn("captured");

		assertThat(service.confirmPayment("pay_1", 42L).isStatus()).isTrue();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(order.getPaymentDetails().getPaymentId()).isEqualTo("pay_1");
		verify(orderRepository).save(order);
	}

	@Test
	void paymentThatIsNotCapturedDoesNotChangeTheOrder() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPaymentStatus("pay_2")).thenReturn("authorized");

		service.confirmPayment("pay_2", 42L);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.PENDING);
		verify(orderRepository, never()).save(any());
	}

	@Test
	void gatewayErrorDuringConfirmationIsReportedAndChangesNothing() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPaymentStatus("pay_3")).thenThrow(new RazorpayException("timeout"));

		assertThatThrownBy(() -> service.confirmPayment("pay_3", 42L))
				.isInstanceOf(RazorpayException.class)
				.hasMessageContaining("timeout");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any());
	}

	@Test
	void confirmationForAnUnknownOrderNeverCallsTheGateway() throws Exception {
		when(orderService.findOrderById(404L)).thenThrow(new OrderException("order not exist with id 404"));

		assertThatThrownBy(() -> service.confirmPayment("pay_1", 404L)).isInstanceOf(OrderException.class);
		verifyNoInteractions(gateway);
	}
}
