package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import com.bazaarly.modal.Product;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.service.OrderService;
import com.bazaarly.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stock under concurrency (docs/CONCURRENCY.md). Every test releases its requests together through a
 * CountDownLatch start gate and runs 20 times, because a race that passes once can still fail on the next run.
 */
class StockConcurrencyIntegrationTest extends IntegrationTest {

	private static final String ADDRESS = "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\","
			+ "\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}";
	private static final int BUYERS = 50;
	private static final int STOCK = 10;

	/** Signing up costs a BCrypt hash, so the 50 buyers are created once and their carts emptied per run. */
	private static final List<String> buyers = new ArrayList<>();

	@Autowired JdbcTemplate jdbc;
	@Autowired OrderService orderService;

	// ---------- 3d: 50 buyers, 10 in stock ----------

	@RepeatedTest(20)
	void fiftyConcurrentBuyersForTenItemsGiveExactlyTenOrders() throws Exception {
		Product product = productWithStock(STOCK, "M");
		List<String> tokens = buyers(BUYERS);
		for (String token : tokens) {
			emptyCart(token);
			addToCart(token, product, "M", 1);
		}

		List<MockHttpServletResponse> responses = concurrently(tokens.stream()
				.<Callable<MockHttpServletResponse>>map(token -> () -> placeOrder(token)).toList());

		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
		assertThat(statuses).containsOnly(200, 409);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(STOCK);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(BUYERS - STOCK);
		for (MockHttpServletResponse refused : responses.stream().filter(r -> r.getStatus() == 409).toList()) {
			JsonNode body = readJson(refused.getContentAsString());
			assertThat(body.get("productId").asLong()).isEqualTo(product.getId());
			assertThat(body.get("size").asText()).isEqualTo("M");
			assertThat(body.get("available").asInt()).isZero();
		}
		assertThat(stock(product)).isZero();
		assertThat(sizeStock(product, "M")).isZero();
		assertThat(ordersFor(product)).isEqualTo(STOCK);
	}

	// ---------- 3d: two products, opposite order ----------

	@RepeatedTest(20)
	void ordersWithTheSameProductsInOppositeOrderDoNotDeadlock() throws Exception {
		Product a = productWithStock(100, "M");
		Product b = productWithStock(100, "M");
		List<String> tokens = buyers(20);
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			emptyCart(token);
			// half the buyers add A then B, the other half B then A
			addToCart(token, i % 2 == 0 ? a : b, "M", 1);
			addToCart(token, i % 2 == 0 ? b : a, "M", 1);
		}

		List<MockHttpServletResponse> responses = concurrently(tokens.stream()
				.<Callable<MockHttpServletResponse>>map(token -> () -> placeOrder(token)).toList());
		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();

		assertThat(statuses).containsOnly(200); // a deadlock aborts one transaction and shows up as a 500
		assertThat(stock(a)).isEqualTo(80);
		assertThat(stock(b)).isEqualTo(80);
		assertThat(sizeStock(a, "M")).isEqualTo(80);
		assertThat(sizeStock(b, "M")).isEqualTo(80);
	}

	// ---------- 3d: releasing stock happens once ----------

	@RepeatedTest(20)
	void cancelAndExpireAtTheSameTimeReleaseTheStockOnce() throws Exception {
		Product product = productWithStock(STOCK, "M");
		String token = buyers(1).get(0);
		emptyCart(token);
		addToCart(token, product, "M", 3);
		long orderId = orderId(placeOrder(token));
		String admin = adminToken();
		assertThat(stock(product)).isEqualTo(7);

		concurrently(List.of(
				() -> mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
						.andReturn().getResponse(),
				() -> orderService.expireIfUnpaid(orderId, LocalDateTime.now().plusMinutes(1)),
				() -> mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
						.andReturn().getResponse()));
		// and once more, one after the other
		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted());
		orderService.expireIfUnpaid(orderId, LocalDateTime.now().plusMinutes(1));

		assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
		assertThat(stock(product)).isEqualTo(STOCK);
		assertThat(sizeStock(product, "M")).isEqualTo(STOCK);
	}

	@RepeatedTest(20)
	void deleteAndExpireAtTheSameTimeReleaseTheStockOnce() throws Exception {
		Product product = productWithStock(STOCK, "M");
		String token = buyers(1).get(0);
		emptyCart(token);
		addToCart(token, product, "M", 3);
		long orderId = orderId(placeOrder(token));
		String admin = adminToken();

		concurrently(List.of(
				() -> mvc.perform(delete("/api/admin/orders/" + orderId + "/delete").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
						.andReturn().getResponse(),
				() -> orderService.expireIfUnpaid(orderId, LocalDateTime.now().plusMinutes(1))));

		assertThat(stock(product)).isEqualTo(STOCK);
		assertThat(sizeStock(product, "M")).isEqualTo(STOCK);
	}

	// ---------- 3d: a payment and the expiry arrive together ----------

	@RepeatedTest(20)
	void paymentArrivingWhileTheOrderExpiresEitherPlacesItOrRefundsIt() throws Exception {
		Product product = productWithStock(STOCK, "M");
		String token = buyers(1).get(0);
		emptyCart(token);
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/" + orderId, "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());
		int amount = jdbc.queryForObject("SELECT total_discounted_price FROM orders WHERE id = ?", Integer.class, orderId);
		String paymentId = "pay_race_" + orderId;
		when(paymentGateway.fetchPayment(paymentId))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, amount * 100L));
		when(paymentGateway.refundInFull(eq(paymentId), anyLong(), anyString()))
				.thenReturn(new PaymentGateway.Refund("rfnd_" + orderId, "processed"));

		concurrently(List.of(
				() -> mvc.perform(get("/api/payments").param("payment_id", paymentId).param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn().getResponse(),
				() -> orderService.expireIfUnpaid(orderId, LocalDateTime.now().plusMinutes(1))));

		String orderStatus = orderStatus(orderId);
		String paymentStatus = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
		if (orderStatus.equals("PLACED")) {
			// the payment won: the order keeps its stock and is not refunded
			assertThat(paymentStatus).isEqualTo("COMPLETED");
			assertThat(stock(product)).isEqualTo(STOCK - 1);
		} else {
			// the expiry won: the stock came back once and the late payment was refunded
			assertThat(orderStatus).isEqualTo("CANCELLED");
			assertThat(paymentStatus).isEqualTo("REFUNDED");
			assertThat(stock(product)).isEqualTo(STOCK);
		}
		assertThat(sizeStock(product, "M")).isEqualTo(stock(product));
	}

	// ---------- helpers ----------

	private List<String> buyers(int count) throws Exception {
		synchronized (buyers) {
			while (buyers.size() < count) {
				buyers.add(newUserToken());
			}
			return List.copyOf(buyers.subList(0, count));
		}
	}

	private Product productWithStock(int quantity, String size) throws Exception {
		Product product = createProduct("stock_concurrency", 1000, 800, "Red", size);
		jdbc.update("UPDATE product SET quantity = ? WHERE id = ?", quantity, product.getId());
		jdbc.update("UPDATE product_sizes SET quantity = ? WHERE product_id = ?", quantity, product.getId());
		return product;
	}

	private void emptyCart(String token) throws Exception {
		JsonNode cart = readJson(mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andReturn().getResponse().getContentAsString());
		for (JsonNode item : cart.get("cartItems")) {
			mvc.perform(delete("/api/cart_items/" + item.get("id").asLong()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().is2xxSuccessful());
		}
	}

	private void addToCart(String token, Product product, String size, int quantity) throws Exception {
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", size, "quantity", quantity))))
				.andExpect(status().isAccepted());
	}

	private MockHttpServletResponse placeOrder(String token) throws Exception {
		return mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(ADDRESS)).andReturn().getResponse();
	}

	private long orderId(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).isEqualTo(200);
		return readJson(response.getContentAsString()).get("id").asLong();
	}

	/** Runs the tasks on separate threads, released together, and returns the results of the ones that return something. */
	@SuppressWarnings("unchecked")
	private <T> List<T> concurrently(List<? extends Callable<?>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Callable<?> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<?> future : futures) {
				Object result = future.get(60, TimeUnit.SECONDS);
				if (result instanceof MockHttpServletResponse) {
					results.add((T) result);
				}
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private int stock(Product product) {
		return jdbc.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, product.getId());
	}

	private int sizeStock(Product product, String size) {
		return jdbc.queryForObject("SELECT quantity FROM product_sizes WHERE product_id = ? AND name = ?", Integer.class,
				product.getId(), size);
	}

	private int ordersFor(Product product) {
		return jdbc.queryForObject("SELECT count(DISTINCT order_id) FROM order_item WHERE product_id = ? AND order_id IS NOT NULL",
				Integer.class, product.getId());
	}

	private String orderStatus(long orderId) {
		return jdbc.queryForObject("SELECT order_status FROM orders WHERE id = ?", String.class, orderId);
	}
}
