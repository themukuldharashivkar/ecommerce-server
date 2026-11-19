package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;
import com.bazaarly.notification.OrderNotificationStore;
import com.bazaarly.notification.OrderPaidEvent;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.payment.PaymentGateway.ProviderPayment;
import com.bazaarly.payment.PaymentService;
import com.bazaarly.repository.CartItemRepository;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.response.PaymentLinkResponse;
import com.bazaarly.service.OrderService;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;
import com.razorpay.RazorpayException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	static final String OWNER = "asha@example.com";

	@Mock PaymentGateway gateway;
	@Mock OrderService orderService;
	@Mock OrderRepository orderRepository;
	@Mock OrderNotificationStore notificationStore;
	@Mock ApplicationEventPublisher events;
	@Mock CartItemRepository cartItemRepository;

	static final java.time.Clock CLOCK = java.time.Clock.fixed(java.time.Instant.parse("2026-11-18T10:00:00Z"), java.time.ZoneOffset.UTC);

	PaymentService service;
	Order order;

	@BeforeEach
	void setUp() {
		// a TransactionTemplate over a mock manager just runs the callback
		TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
		service = new PaymentService(gateway, orderService, orderRepository, notificationStore, events, transaction,
				"https://shop.example", cartItemRepository, java.time.Duration.ofMinutes(60), CLOCK);
		User user = new User();
		user.setFirstName("Asha");
		user.setLastName("Rao");
		user.setEmail(OWNER);
		user.setMobile("9876543210");
		order = new Order();
		order.setId(42L);
		order.setUser(user);
		order.setTotalPrice(1499);          // MRP
		order.setTotalDiscountedPrice(1199); // what the shopper pays
		order.setOrderStatus(OrderStatus.PENDING);
		order.setCreatedAt(java.time.LocalDateTime.now(CLOCK)); // placed just now
		order.getPaymentDetails().setStatus(PaymentStatus.PENDING);
	}

	private static ProviderPayment captured(String providerOrderId, long amount) {
		return new ProviderPayment("captured", providerOrderId, amount);
	}

	/** Order with a payment link already created for provider order "order_rzp_1". */
	private void orderWithLink() throws Exception {
		order.setOrderId("order_rzp_1");
		when(orderService.findOrderById(42L)).thenReturn(order);
	}

	// ---------- payment link ----------

	@Test
	void createPaymentLinkSendsTheDiscountedTotalInPaiseCustomerAndReturnUrl() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.createPaymentLink(any()))
				.thenReturn(new PaymentGateway.CreatedPaymentLink("plink_1", "https://rzp.io/x", "order_rzp_1"));

		PaymentLinkResponse response = service.createPaymentLink(42L, OWNER);

		ArgumentCaptor<PaymentGateway.PaymentLinkRequest> sent = ArgumentCaptor.forClass(PaymentGateway.PaymentLinkRequest.class);
		verify(gateway).createPaymentLink(sent.capture());
		assertThat(sent.getValue().amountInPaise()).isEqualTo(119900L); // discounted total, not the 1499 MRP
		assertThat(sent.getValue().customerName()).isEqualTo("Asha Rao");
		assertThat(sent.getValue().customerEmail()).isEqualTo(OWNER);
		assertThat(sent.getValue().callbackUrl()).isEqualTo("https://shop.example/payment/42");
		assertThat(response.getPayment_link_url()).isEqualTo("https://rzp.io/x");
		assertThat(order.getOrderId()).isEqualTo("order_rzp_1");
		verify(orderRepository).save(order);
	}

	@Test
	void anotherUsersOrderLooksLikeAnUnknownOrder() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);

		assertThatThrownBy(() -> service.createPaymentLink(42L, "mallory@example.com"))
				.isInstanceOf(OrderException.class).hasMessage("order not exist with id 42");
		assertThatThrownBy(() -> service.confirmPayment("pay_1", 42L, "mallory@example.com"))
				.isInstanceOf(OrderException.class).hasMessage("order not exist with id 42");
		verifyNoInteractions(gateway);
	}

	@Test
	void paidOrderCannotGetANewLink() throws Exception {
		order.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		when(orderService.findOrderById(42L)).thenReturn(order);

		assertThatThrownBy(() -> service.createPaymentLink(42L, OWNER)).hasMessage("order 42 is already paid");
		verifyNoInteractions(gateway);
	}

	@Test
	void orderWithNothingToPayNeverCallsTheGateway() throws Exception {
		order.setTotalDiscountedPrice(0);
		when(orderService.findOrderById(42L)).thenReturn(order);

		assertThatThrownBy(() -> service.createPaymentLink(42L, OWNER)).hasMessage("order 42 has nothing to pay");
		verifyNoInteractions(gateway);
	}

	@Test
	void gatewayFailureWhileCreatingALinkLeavesTheOrderUntouched() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.createPaymentLink(any())).thenThrow(new RazorpayException("bad key"));

		assertThatThrownBy(() -> service.createPaymentLink(42L, OWNER)).isInstanceOf(RazorpayException.class);
		verify(orderRepository, never()).save(any());
	}

	// ---------- confirmation ----------

	@Test
	void capturedPaymentForTheOrderMarksItPaidRecordsANotificationAndPublishesTheEvent() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_1")).thenReturn(captured("order_rzp_1", 119900));
		when(orderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
		when(notificationStore.createIfAbsent(42L)).thenReturn(true);

		assertThat(service.confirmPayment("pay_1", 42L, OWNER).isStatus()).isTrue();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(order.getPaymentDetails().getPaymentId()).isEqualTo("pay_1");
		verify(orderRepository).save(order);
		verify(events).publishEvent(new OrderPaidEvent(42L));
	}

	@Test
	void confirmingAnAlreadyPaidOrderIsANoOp() throws Exception {
		order.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		when(orderService.findOrderById(42L)).thenReturn(order);

		assertThat(service.confirmPayment("pay_1", 42L, OWNER).isStatus()).isTrue();

		verifyNoInteractions(gateway, notificationStore, events);
		verify(orderRepository, never()).save(any());
	}

	@Test
	void concurrentConfirmationThatLosesTheRaceDoesNothing() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_1")).thenReturn(captured("order_rzp_1", 119900));
		Order alreadyPaidByTheOtherRequest = new Order();
		alreadyPaidByTheOtherRequest.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		when(orderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(alreadyPaidByTheOtherRequest));

		service.confirmPayment("pay_1", 42L, OWNER);

		verify(orderRepository, never()).save(any());
		verifyNoInteractions(notificationStore, events);
	}

	@Test
	void noEventWhenTheNotificationAlreadyExists() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_1")).thenReturn(captured("order_rzp_1", 119900));
		when(orderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
		when(notificationStore.createIfAbsent(42L)).thenReturn(false);

		service.confirmPayment("pay_1", 42L, OWNER);

		verify(events, never()).publishEvent(any());
	}

	// ---------- Razorpay's real behaviour: no order id until the link is paid ----------

	@Test
	void paymentIsMatchedThroughThePaymentLinkWhenNoOrderIdWasKnownAtCreation() throws Exception {
		order.getPaymentDetails().setRazorpayPaymentLinkId("plink_1"); // order.getOrderId() stays null
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPayment("pay_1")).thenReturn(captured("order_created_at_payment", 119900));
		when(gateway.fetchPaymentLink("plink_1")).thenReturn(new PaymentGateway.PaymentLinkState(
				"plink_1", "https://rzp.io/x", "paid", "order_created_at_payment", java.util.List.of("pay_1")));
		when(orderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
		when(notificationStore.createIfAbsent(42L)).thenReturn(true);

		service.confirmPayment("pay_1", 42L, OWNER);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
	}

	@Test
	void paymentThatIsNotOnTheOrdersLinkIsRejected() throws Exception {
		order.getPaymentDetails().setRazorpayPaymentLinkId("plink_1");
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPayment("pay_other")).thenReturn(captured("order_other", 119900));
		when(gateway.fetchPaymentLink("plink_1")).thenReturn(new PaymentGateway.PaymentLinkState(
				"plink_1", "https://rzp.io/x", "created", null, java.util.List.of()));

		assertThatThrownBy(() -> service.confirmPayment("pay_other", 42L, OWNER))
				.hasMessage("payment pay_other does not belong to order 42");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void anOrdersStillPayableLinkIsReusedInsteadOfCreatingAnother() throws Exception {
		order.getPaymentDetails().setRazorpayPaymentLinkId("plink_1");
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPaymentLink("plink_1")).thenReturn(new PaymentGateway.PaymentLinkState(
				"plink_1", "https://rzp.io/existing", "created", null, java.util.List.of()));

		PaymentLinkResponse response = service.createPaymentLink(42L, OWNER);

		assertThat(response.getPayment_link_url()).isEqualTo("https://rzp.io/existing");
		verify(gateway, never()).createPaymentLink(any());
	}

	@Test
	void anExpiredLinkIsReplacedAndTheNewLinkIdStored() throws Exception {
		order.getPaymentDetails().setRazorpayPaymentLinkId("plink_old");
		when(orderService.findOrderById(42L)).thenReturn(order);
		when(gateway.fetchPaymentLink("plink_old")).thenReturn(new PaymentGateway.PaymentLinkState(
				"plink_old", "https://rzp.io/old", "expired", null, java.util.List.of()));
		when(gateway.createPaymentLink(any())).thenReturn(new PaymentGateway.CreatedPaymentLink("plink_new", "https://rzp.io/new", null));

		assertThat(service.createPaymentLink(42L, OWNER).getPayment_link_url()).isEqualTo("https://rzp.io/new");
		assertThat(order.getPaymentDetails().getRazorpayPaymentLinkId()).isEqualTo("plink_new");
	}

	@Test
	void paymentForAnotherProviderOrderIsRejected() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_other")).thenReturn(captured("order_rzp_SOMEONE_ELSE", 119900));

		assertThatThrownBy(() -> service.confirmPayment("pay_other", 42L, OWNER))
				.isInstanceOf(OrderException.class).hasMessage("payment pay_other does not belong to order 42");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		verifyNoInteractions(notificationStore, events);
	}

	@Test
	void paymentWithoutALinkIsRejected() throws Exception {
		when(orderService.findOrderById(42L)).thenReturn(order); // no provider order id stored
		when(gateway.fetchPayment("pay_1")).thenReturn(captured("order_rzp_1", 119900));

		assertThatThrownBy(() -> service.confirmPayment("pay_1", 42L, OWNER)).isInstanceOf(OrderException.class);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void partialPaymentIsRejected() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_cheap")).thenReturn(captured("order_rzp_1", 100));

		assertThatThrownBy(() -> service.confirmPayment("pay_cheap", 42L, OWNER))
				.hasMessage("payment pay_cheap amount does not match order 42");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void paymentThatIsNotCapturedDoesNotChangeTheOrder() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_2")).thenReturn(new ProviderPayment("authorized", "order_rzp_1", 119900));

		service.confirmPayment("pay_2", 42L, OWNER);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any());
		verifyNoInteractions(notificationStore, events);
	}

	@Test
	void gatewayErrorDuringConfirmationIsReportedAndChangesNothing() throws Exception {
		orderWithLink();
		when(gateway.fetchPayment("pay_3")).thenThrow(new RazorpayException("timeout"));

		assertThatThrownBy(() -> service.confirmPayment("pay_3", 42L, OWNER))
				.isInstanceOf(RazorpayException.class).hasMessageContaining("timeout");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any());
	}

	@Test
	void confirmationForAnUnknownOrderNeverCallsTheGateway() throws Exception {
		when(orderService.findOrderById(404L)).thenThrow(new OrderException("order not exist with id 404"));

		assertThatThrownBy(() -> service.confirmPayment("pay_1", 404L, OWNER)).isInstanceOf(OrderException.class);
		verifyNoInteractions(gateway);
	}
}
