package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.bazaarly.modal.Product;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.service.OrderExpiryJob;
import com.bazaarly.support.IntegrationTest;

/**
 * Stock is reserved when an order is placed, given back when it is cancelled or expires unpaid, and can
 * never go below zero. The cart keeps its items until the order is paid, then loses only those items.
 * (Each product created here has 10 in stock: size M 5, size L 5.)
 */
class StockAndCartIntegrationTest extends IntegrationTest {

	private static final String ADDRESS = "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\","
			+ "\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}";

	@Autowired JdbcTemplate jdbc;
	@Autowired OrderExpiryJob expiryJob;

	private ResultActions addToCart(String token, Product product, String size, int quantity) throws Exception {
		return mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", size, "quantity", quantity))));
	}

	private ResultActions placeOrder(String token) throws Exception {
		return mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(ADDRESS));
	}

	private long orderId(ResultActions result) throws Exception {
		return readJson(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
	}

	private int stock(Product product) {
		return jdbc.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, product.getId());
	}

	private int sizeStock(Product product, String size) {
		return jdbc.queryForObject("SELECT quantity FROM product_sizes WHERE product_id = ? AND name = ?", Integer.class,
				product.getId(), size);
	}

	private int cartLines(String token) throws Exception {
		return readJson(mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andReturn().getResponse().getContentAsString()).get("cartItems").size();
	}

	private void pay(String token, long orderId) throws Exception {
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/" + orderId, "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());
		int amount = jdbc.queryForObject("SELECT total_discounted_price FROM orders WHERE id = ?", Integer.class, orderId);
		when(paymentGateway.fetchPayment("pay_" + orderId))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, amount * 100L));
		mvc.perform(get("/api/payments").param("payment_id", "pay_" + orderId).param("order_id", String.valueOf(orderId))
				.header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
	}

	// ---------- reservation ----------

	@Test
	void placingAnOrderReservesTheStockAndTheProductPageShowsIt() throws Exception {
		Product product = createProduct("stock_tee", 1000, 800, "Red", "M", "L");
		mvc.perform(get("/api/products/id/" + product.getId())).andExpect(jsonPath("$.quantity").value(10)); // now cached
		String token = newUserToken();
		addToCart(token, product, "M", 3).andExpect(status().isAccepted());

		placeOrder(token).andExpect(status().isOk());

		assertThat(stock(product)).isEqualTo(7);
		assertThat(sizeStock(product, "M")).isEqualTo(2);
		assertThat(sizeStock(product, "L")).isEqualTo(5);
		// the cached product page was evicted, so it shows the new stock
		mvc.perform(get("/api/products/id/" + product.getId())).andExpect(jsonPath("$.quantity").value(7));
	}

	@Test
	void twoOrdersInARowKeepOneRowPerSize() throws Exception {
		// regression: Hibernate used to rewrite product_sizes from stale memory on flush, duplicating sizes
		Product product = createProduct("stock_rows", 1000, 800, "Red", "M", "L");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		placeOrder(token).andExpect(status().isOk());
		placeOrder(token).andExpect(status().isOk()); // cart still holds the item until it is paid

		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_sizes WHERE product_id = ?", Integer.class, product.getId()))
				.isEqualTo(2);
		assertThat(sizeStock(product, "M")).isEqualTo(3);
		assertThat(stock(product)).isEqualTo(8);
	}

	@Test
	void cannotAddMoreThanIsInStock() throws Exception {
		Product product = createProduct("stock_cart", 1000, 800, "Red", "M", "L");
		String token = newUserToken();

		addToCart(token, product, "M", 6).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Only 5 left of " + product.getTitle() + " in size M"));
		addToCart(token, product, "XL", 1).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Size XL is not available for this product"));
		assertThat(cartLines(token)).isZero();
	}

	@Test
	void orderIsRefusedAndNothingReservedWhenAnyItemRanOut() throws Exception {
		Product plenty = createProduct("stock_atomic", 1000, 800, "Red", "M", "L");
		Product scarce = createProduct("stock_atomic", 1000, 800, "Blue", "M", "L");
		String token = newUserToken();
		addToCart(token, plenty, "M", 2);
		addToCart(token, scarce, "L", 4);
		jdbc.update("UPDATE product_sizes SET quantity = 1 WHERE product_id = ? AND name = 'L'", scarce.getId()); // sold elsewhere

		placeOrder(token).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("Only 1 left of " + scarce.getTitle() + " in size L"))
				.andExpect(jsonPath("$.productId").value(scarce.getId()))
				.andExpect(jsonPath("$.size").value("L"))
				.andExpect(jsonPath("$.available").value(1));

		// all or nothing: the first item's stock was not taken either, and no order exists
		assertThat(sizeStock(plenty, "M")).isEqualTo(5);
		assertThat(stock(plenty)).isEqualTo(10);
		mvc.perform(get("/api/orders/user").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void theLastItemCanOnlyBeSoldOnceUnderConcurrency() throws Exception {
		Product product = createProduct("stock_race", 1000, 800, "Red", "M");
		jdbc.update("UPDATE product_sizes SET quantity = 1 WHERE product_id = ?", product.getId());
		jdbc.update("UPDATE product SET quantity = 1 WHERE id = ?", product.getId());
		int buyers = 6;
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < buyers; i++) {
			String token = newUserToken();
			addToCart(token, product, "M", 1).andExpect(status().isAccepted()); // all see 1 in stock
			tokens.add(token);
		}

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(buyers);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (String token : tokens) {
				results.add(pool.submit(() -> {
					start.await();
					return placeOrder(token).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(statuses).containsOnly(200, 409);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(stock(product)).isZero();
		assertThat(sizeStock(product, "M")).isZero();
	}

	// ---------- release ----------

	@Test
	void cancellingAnOrderGivesTheStockBackOnce() throws Exception {
		Product product = createProduct("stock_cancel", 1000, 800, "Red", "M", "L");
		String token = newUserToken();
		addToCart(token, product, "M", 2);
		long orderId = orderId(placeOrder(token));
		String admin = adminToken();

		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.orderStatus").value("CANCELLED"));
		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted());

		assertThat(stock(product)).isEqualTo(10);
		assertThat(sizeStock(product, "M")).isEqualTo(5);
	}

	@Test
	void shippedOrdersCannotBeCancelled() throws Exception {
		Product product = createProduct("stock_shipped", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));
		String admin = adminToken();
		mvc.perform(put("/api/admin/orders/" + orderId + "/ship").header(HttpHeaders.AUTHORIZATION, bearer(admin)));

		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest());
		assertThat(stock(product)).isEqualTo(9);
	}

	@Test
	void unpaidOrdersExpireAndReleaseTheirStockButPaidOnesDoNot() throws Exception {
		Product product = createProduct("stock_expiry", 1000, 800, "Red", "M", "L");
		String unpaidBuyer = newUserToken();
		addToCart(unpaidBuyer, product, "M", 2);
		long unpaid = orderId(placeOrder(unpaidBuyer));
		String paidBuyer = newUserToken();
		addToCart(paidBuyer, product, "L", 1);
		long paid = orderId(placeOrder(paidBuyer));
		pay(paidBuyer, paid);
		assertThat(stock(product)).isEqualTo(7);

		// both orders are now older than the reservation (60 min) plus grace (5 min)
		jdbc.update("UPDATE orders SET created_at = now() - interval '2 hours' WHERE id IN (?, ?)", unpaid, paid);
		expiryJob.expireUnpaidOrders();

		assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id = ?", String.class, unpaid)).isEqualTo("CANCELLED");
		assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id = ?", String.class, paid)).isEqualTo("PLACED");
		assertThat(stock(product)).isEqualTo(9);            // the unpaid 2 came back, the paid 1 did not
		assertThat(sizeStock(product, "M")).isEqualTo(5);
		assertThat(sizeStock(product, "L")).isEqualTo(4);

		expiryJob.expireUnpaidOrders();                     // running again changes nothing
		assertThat(stock(product)).isEqualTo(9);
	}

	@Test
	void recentUnpaidOrdersAreNotExpired() throws Exception {
		Product product = createProduct("stock_recent", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));

		expiryJob.expireUnpaidOrders();

		assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("PENDING");
		assertThat(stock(product)).isEqualTo(9);
	}

	@Test
	void paymentForAnExpiredOrderIsRefused() throws Exception {
		Product product = createProduct("stock_late", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/x", "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());
		jdbc.update("UPDATE orders SET created_at = now() - interval '2 hours' WHERE id = ?", orderId);
		expiryJob.expireUnpaidOrders();
		when(paymentGateway.fetchPayment("pay_late"))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, 80000));
		when(paymentGateway.refundInFull("pay_late", 80000, "Order " + orderId + " expired before payment"))
				.thenReturn(new PaymentGateway.Refund("rfnd_late", "processed"));

		mvc.perform(get("/api/payments").param("payment_id", "pay_late").param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("This order expired before the payment completed. Your payment has been refunded."));
		assertThat(stock(product)).isEqualTo(10); // the released stock was not taken again
		// the late payment is refunded automatically and recorded on the cancelled order
		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("SELECT razorpay_refund_id FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("rfnd_late");
		assertThat(jdbc.queryForObject("SELECT payment_id FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("pay_late");
	}

	// ---------- payment link expiry ----------

	@Test
	void paymentLinkExpiresWhenTheReservationDoes() throws Exception {
		Product product = createProduct("stock_link", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/x", "order_rzp_" + orderId));

		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());

		ArgumentCaptor<PaymentGateway.PaymentLinkRequest> sent = ArgumentCaptor.forClass(PaymentGateway.PaymentLinkRequest.class);
		org.mockito.Mockito.verify(paymentGateway).createPaymentLink(sent.capture());
		Instant created = jdbc.queryForObject("SELECT created_at FROM orders WHERE id = ?", java.time.LocalDateTime.class, orderId)
				.atZone(ZoneId.systemDefault()).toInstant();
		assertThat(sent.getValue().expireBy()).isEqualTo(created.plusSeconds(3600).getEpochSecond());
	}

	@Test
	void noNewPaymentLinkForAnOrderAboutToExpire() throws Exception {
		Product product = createProduct("stock_link_late", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);
		long orderId = orderId(placeOrder(token));
		jdbc.update("UPDATE orders SET created_at = now() - interval '50 minutes' WHERE id = ?", orderId); // 10 min left

		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("This order is about to expire. Please place it again."));
	}

	// ---------- cart ----------

	@Test
	void cartKeepsItsItemsUntilTheOrderIsPaid() throws Exception {
		Product product = createProduct("cart_pending", 1000, 800, "Red", "M");
		String token = newUserToken();
		addToCart(token, product, "M", 1);

		placeOrder(token).andExpect(status().isOk());

		assertThat(cartLines(token)).isEqualTo(1); // an abandoned payment does not lose the cart
	}

	@Test
	void payingRemovesExactlyTheOrderedItemsFromTheCart() throws Exception {
		Product ordered = createProduct("cart_paid", 1000, 800, "Red", "M", "L");
		Product addedLater = createProduct("cart_paid", 1000, 800, "Blue", "M");
		String token = newUserToken();
		addToCart(token, ordered, "M", 1);
		long orderId = orderId(placeOrder(token));
		addToCart(token, addedLater, "M", 1);  // added after the order was placed
		addToCart(token, ordered, "L", 1);     // same product, other size, also later

		pay(token, orderId);

		JsonNodeAssert cart = new JsonNodeAssert(readJson(mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andReturn().getResponse().getContentAsString()));
		cart.hasLines(2);
		cart.contains(addedLater.getId(), "M");
		cart.contains(ordered.getId(), "L");
	}

	/** Small helper for reading the cart JSON. */
	private record JsonNodeAssert(com.fasterxml.jackson.databind.JsonNode cart) {
		void hasLines(int expected) {
			assertThat(cart.get("cartItems").size()).isEqualTo(expected);
		}

		void contains(long productId, String size) {
			boolean found = false;
			for (com.fasterxml.jackson.databind.JsonNode line : cart.get("cartItems")) {
				found |= line.get("product").get("id").asLong() == productId && line.get("size").asText().equals(size);
			}
			assertThat(found).as("cart contains product %d size %s", productId, size).isTrue();
		}
	}
}
