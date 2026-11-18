package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.bazaarly.notification.OrderConfirmationJob;
import com.bazaarly.notification.OrderConfirmationMessage;
import com.bazaarly.notification.OrderNotificationStore;
import com.bazaarly.notification.OrderNotificationStore.NotificationState;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.support.IntegrationTest;
import com.bazaarly.user.domain.OrderStatus;

/**
 * The background order-confirmation job: async after payment confirmation, exactly one notification per
 * order however often (or however concurrently) the confirmation is called, and retries after failures.
 */
class OrderConfirmationIntegrationTest extends IntegrationTest {

	@Autowired OrderNotificationStore notifications;
	@Autowired OrderConfirmationJob job;
	@Autowired OrderRepository orders;
	@Autowired JdbcTemplate jdbc;

	/** A user with a pending order of Rs 700 that has a payment link (provider order "order_rzp_<id>"). */
	record PendingOrder(String token, String email, long id) {
		String paymentId() {
			return "pay_" + id;
		}
	}

	private PendingOrder pendingOrderWithLink() throws Exception {
		String email = uniqueEmail("buyer");
		String token = signUp(email);
		long productId = createProduct("confirm_tee", 1000, 700, "Blue", "M").getId();
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", productId, "size", "M", "quantity", 1))))
				.andExpect(status().isAccepted());
		String order = mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\",\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long orderId = readJson(order).get("id").asLong();

		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/" + orderId, "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted());
		return new PendingOrder(token, email, orderId);
	}

	private void razorpayReportsCaptured(PendingOrder order) throws Exception {
		when(paymentGateway.fetchPayment(order.paymentId()))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + order.id(), 70000));
	}

	private ResultActions confirm(PendingOrder order, String token) throws Exception {
		return mvc.perform(get("/api/payments").param("payment_id", order.paymentId())
				.param("order_id", String.valueOf(order.id())).header(HttpHeaders.AUTHORIZATION, bearer(token)));
	}

	/** How many times the sender was asked to deliver this order's confirmation. */
	private long deliveries(long orderId) {
		return mockingDetails(confirmationSender).getInvocations().stream()
				.filter(i -> i.getMethod().getName().equals("send"))
				.filter(i -> ((OrderConfirmationMessage) i.getArgument(0)).orderId() == orderId)
				.count();
	}

	private void awaitStatus(long orderId, String expected) {
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			NotificationState state = notifications.find(orderId);
			assertThat(state).isNotNull();
			assertThat(state.status()).isEqualTo(expected);
		});
	}

	@Test
	void confirmationReturnsBeforeTheSlowEmailIsSent() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(inv -> {
			// the "mail server" is stuck until the test lets it go (bounded, so a broken build fails instead of hanging)
			release.await(5, java.util.concurrent.TimeUnit.SECONDS);
			return null;
		}).when(confirmationSender).send(argThat(m -> m.orderId() == order.id()));

		long start = System.nanoTime();
		confirm(order, order.token()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(true));
		long millis = (System.nanoTime() - start) / 1_000_000;

		// the HTTP response came back while the email was still blocked
		assertThat(millis).isLessThan(2_000);
		assertThat(orders.findById(order.id()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PLACED);
		awaitStatus(order.id(), "SENDING");

		release.countDown();
		awaitStatus(order.id(), "SENT");
		assertThat(deliveries(order.id())).isEqualTo(1);
	}

	@Test
	void emailGoesToTheBuyerWithTheOrderAndTotal() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);

		confirm(order, order.token()).andExpect(status().isOk());
		awaitStatus(order.id(), "SENT");

		OrderConfirmationMessage sent = (OrderConfirmationMessage) mockingDetails(confirmationSender).getInvocations().stream()
				.filter(i -> ((OrderConfirmationMessage) i.getArgument(0)).orderId() == order.id())
				.findFirst().orElseThrow().getArgument(0);
		assertThat(sent.to()).isEqualTo(order.email());
		assertThat(sent.subject()).isEqualTo("Bazaarly order #" + order.id() + " confirmed");
		assertThat(sent.body()).contains("Total paid: Rs 700").contains("1 x Product").contains("Pune, MH 411001");
	}

	@Test
	void retriedConfirmationSendsOneEmail() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);

		// e.g. the payment-success page runs its effect twice (React StrictMode) or the user refreshes
		confirm(order, order.token()).andExpect(status().isOk());
		confirm(order, order.token()).andExpect(status().isOk());
		confirm(order, order.token()).andExpect(status().isOk());

		awaitStatus(order.id(), "SENT");
		Thread.sleep(300); // give any (wrong) second job time to run
		assertThat(deliveries(order.id())).isEqualTo(1);
		assertThat(notifications.find(order.id()).attempts()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM order_notification WHERE order_id = ?", Integer.class, order.id()))
				.isEqualTo(1);
	}

	@Test
	void concurrentConfirmationsSendOneEmail() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);
		int callers = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				Callable<Integer> call = () -> {
					start.await();
					return confirm(order, order.token()).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		} finally {
			pool.shutdownNow();
		}

		awaitStatus(order.id(), "SENT");
		Thread.sleep(300);
		assertThat(deliveries(order.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM order_notification WHERE order_id = ?", Integer.class, order.id()))
				.isEqualTo(1);
	}

	@Test
	void failedEmailIsRetriedBySweeperAndSentOnce() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);
		doThrow(new IllegalStateException("SMTP down"))
				.doNothing()
				.when(confirmationSender).send(argThat(m -> m.orderId() == order.id()));

		confirm(order, order.token()).andExpect(status().isOk());
		awaitStatus(order.id(), "FAILED");
		NotificationState failed = notifications.find(order.id());
		assertThat(failed.attempts()).isEqualTo(1);
		assertThat(failed.lastError()).contains("SMTP down");

		job.retryDue();

		NotificationState sent = notifications.find(order.id());
		assertThat(sent.status()).isEqualTo("SENT");
		assertThat(sent.attempts()).isEqualTo(2);
		assertThat(sent.lastError()).isNull();
		job.retryDue(); // nothing left to do for this order
		assertThat(deliveries(order.id())).isEqualTo(2); // one failed attempt, one success
	}

	@Test
	void sweeperGivesUpAfterMaxAttempts() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);
		doThrow(new IllegalStateException("mailbox does not exist"))
				.when(confirmationSender).send(argThat(m -> m.orderId() == order.id()));

		confirm(order, order.token()).andExpect(status().isOk());
		awaitStatus(order.id(), "FAILED");
		for (int i = 0; i < 6; i++) {
			job.retryDue();
		}

		NotificationState state = notifications.find(order.id());
		assertThat(state.status()).isEqualTo("FAILED");
		assertThat(state.attempts()).isEqualTo(5);
		assertThat(deliveries(order.id())).isEqualTo(5);
	}

	@Test
	void notificationTheAsyncJobNeverStartedIsSentByTheSweeper() throws Exception {
		// as if the app restarted between commit and the async job, or the queue was full
		PendingOrder order = pendingOrderWithLink();
		jdbc.update("UPDATE orders SET status = 'COMPLETED', order_status = 'PLACED' WHERE id = ?", order.id());
		assertThat(notifications.createIfAbsent(order.id())).isTrue();
		assertThat(notifications.createIfAbsent(order.id())).isFalse(); // at most one row per order
		doNothing().when(confirmationSender).send(any());

		job.retryDue();

		assertThat(notifications.find(order.id()).status()).isEqualTo("SENT");
		assertThat(deliveries(order.id())).isEqualTo(1);
	}

	@Test
	void anotherUserCannotConfirmSomeoneElsesOrder() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		razorpayReportsCaptured(order);

		confirm(order, newUserToken())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("order not exist with id " + order.id()));

		assertThat(orders.findById(order.id()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(notifications.find(order.id())).isNull();
	}

	@Test
	void paymentFromAnotherOrderCannotPayThisOne() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		when(paymentGateway.fetchPayment(order.paymentId()))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_some_other_order", 70000));

		confirm(order, order.token())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("payment " + order.paymentId() + " does not belong to order " + order.id()));

		assertThat(orders.findById(order.id()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(notifications.find(order.id())).isNull();
	}

	@Test
	void underpaymentIsRejected() throws Exception {
		PendingOrder order = pendingOrderWithLink();
		when(paymentGateway.fetchPayment(order.paymentId()))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + order.id(), 100));

		confirm(order, order.token()).andExpect(status().isBadRequest());

		assertThat(orders.findById(order.id()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void anotherUserCannotCreateAPaymentLinkForSomeoneElsesOrder() throws Exception {
		PendingOrder order = pendingOrderWithLink();

		mvc.perform(post("/api/payments/" + order.id()).header(HttpHeaders.AUTHORIZATION, bearer(newUserToken())))
				.andExpect(status().isNotFound());
	}
}
