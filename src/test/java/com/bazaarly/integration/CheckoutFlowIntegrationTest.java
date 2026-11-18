package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.bazaarly.modal.Order;
import com.bazaarly.modal.Product;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.support.IntegrationTest;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.razorpay.RazorpayException;

class CheckoutFlowIntegrationTest extends IntegrationTest {

	private static final String ADDRESS = """
			{"firstName":"Asha","lastName":"Rao","streetAddress":"12 MG Road","city":"Pune",
			 "state":"MH","zipCode":"411001","mobile":"9876543210"}""";

	@Autowired
	OrderRepository orderRepository;

	private void addToCart(String token, Product product, String size) throws Exception {
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", size, "quantity", 1))))
				.andExpect(status().isAccepted());
	}

	private JsonNode cart(String token) throws Exception {
		return readJson(mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	private JsonNode createOrder(String token) throws Exception {
		return readJson(mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(ADDRESS))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	/** Creates the payment link (mocked gateway); its provider order id is what payments are checked against. */
	private void createPaymentLink(String token, long orderId) throws Exception {
		when(paymentGateway.createPaymentLink(any()))
				.thenReturn(new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/" + orderId, "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted());
	}

	@Test
	void cartTotalsFollowTheItemsInIt() throws Exception {
		String token = newUserToken();
		Product shirt = createProduct("checkout_shirt", 1000, 700, "Blue", "M");
		Product jeans = createProduct("checkout_jeans", 2000, 1500, "Black", "32");

		addToCart(token, shirt, "M");
		addToCart(token, jeans, "32");
		JsonNode cart = cart(token);

		assertThat(cart.get("cartItems")).hasSize(2);
		assertThat(cart.get("totalItem").asInt()).isEqualTo(2);
		assertThat(cart.get("totalPrice").asDouble()).isEqualTo(3000.0);
		assertThat(cart.get("totalDiscountedPrice").asInt()).isEqualTo(2200);
		assertThat(cart.get("discounte").asInt()).isEqualTo(800);
	}

	@Test
	void addingTheSameProductAndSizeTwiceKeepsOneLine() throws Exception {
		String token = newUserToken();
		Product shirt = createProduct("checkout_shirt", 1000, 700, "Blue", "M");

		addToCart(token, shirt, "M");
		addToCart(token, shirt, "M");

		assertThat(cart(token).get("cartItems")).hasSize(1);
	}

	@Test
	void cartItemsCanBeUpdatedAndRemovedOnlyByTheirOwner() throws Exception {
		String owner = newUserToken();
		String other = newUserToken();
		Product shirt = createProduct("checkout_shirt", 1000, 700, "Blue", "M");
		addToCart(owner, shirt, "M");
		long itemId = cart(owner).get("cartItems").get(0).get("id").asLong();

		mvc.perform(put("/api/cart_items/" + itemId).header(HttpHeaders.AUTHORIZATION, bearer(other))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
				.andExpect(status().isBadRequest());
		mvc.perform(delete("/api/cart_items/" + itemId).header(HttpHeaders.AUTHORIZATION, bearer(other)))
				.andExpect(status().isBadRequest());

		mvc.perform(put("/api/cart_items/" + itemId).header(HttpHeaders.AUTHORIZATION, bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.quantity").value(3))
				.andExpect(jsonPath("$.price").value(3000))
				.andExpect(jsonPath("$.discountedPrice").value(2100));

		mvc.perform(delete("/api/cart_items/" + itemId).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
				.andExpect(status().isAccepted());
		assertThat(cart(owner).get("cartItems")).isEmpty();
	}

	@Test
	void checkoutCreatesAPendingOrderFromTheCart() throws Exception {
		String token = newUserToken();
		Product shirt = createProduct("checkout_shirt", 1000, 700, "Blue", "M");
		Product jeans = createProduct("checkout_jeans", 2000, 1500, "Black", "32");
		addToCart(token, shirt, "M");
		addToCart(token, jeans, "32");

		JsonNode order = createOrder(token);

		assertThat(order.get("orderStatus").asText()).isEqualTo("PENDING");
		assertThat(order.get("paymentDetails").get("status").asText()).isEqualTo("PENDING");
		assertThat(order.get("orderItems")).hasSize(2);
		assertThat(order.get("totalPrice").asDouble()).isEqualTo(3000.0);
		assertThat(order.get("totalDiscountedPrice").asInt()).isEqualTo(2200);
		assertThat(order.get("totalItem").asInt()).isEqualTo(2);
		assertThat(order.get("shippingAddress").get("city").asText()).isEqualTo("Pune");

		long orderId = order.get("id").asLong();
		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.orderItems.length()").value(2));
	}

	@Test
	void paymentLinkIsCreatedThroughTheGatewayForTheOrderAmount() throws Exception {
		String token = newUserToken();
		addToCart(token, createProduct("checkout_shirt", 1000, 700, "Blue", "M"), "M");
		long orderId = createOrder(token).get("id").asLong();
		when(paymentGateway.createPaymentLink(any()))
				.thenReturn(new PaymentGateway.CreatedPaymentLink("plink_test_1", "https://rzp.io/test", "order_test_1"));

		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.payment_link_url").value("https://rzp.io/test"))
				.andExpect(jsonPath("$.payment_link_id").value("plink_test_1"));

		ArgumentCaptor<PaymentGateway.PaymentLinkRequest> sent = ArgumentCaptor.forClass(PaymentGateway.PaymentLinkRequest.class);
		verify(paymentGateway).createPaymentLink(sent.capture());
		assertThat(sent.getValue().amountInPaise()).isEqualTo(70000L); // discounted total 700, not the 1000 MRP
		assertThat(sent.getValue().callbackUrl()).isEqualTo("http://frontend.test/payment/" + orderId);
		assertThat(orderRepository.findById(orderId).orElseThrow().getOrderId()).isEqualTo("order_test_1");
	}

	@Test
	void capturedPaymentPlacesTheOrder() throws Exception {
		String token = newUserToken();
		addToCart(token, createProduct("checkout_shirt", 1000, 700, "Blue", "M"), "M");
		long orderId = createOrder(token).get("id").asLong();
		createPaymentLink(token, orderId);
		when(paymentGateway.fetchPayment("pay_captured_1"))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, 70000));

		mvc.perform(get("/api/payments").param("payment_id", "pay_captured_1").param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value(true));

		Order order = orderRepository.findById(orderId).orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(order.getPaymentDetails().getPaymentId()).isEqualTo("pay_captured_1");

		// placed orders show up in the user's order history
		mvc.perform(get("/api/orders/user").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$[0].id").value(orderId));
	}

	@Test
	void paymentThatWasNotCapturedLeavesTheOrderPending() throws Exception {
		String token = newUserToken();
		addToCart(token, createProduct("checkout_shirt", 1000, 700, "Blue", "M"), "M");
		long orderId = createOrder(token).get("id").asLong();
		createPaymentLink(token, orderId);
		when(paymentGateway.fetchPayment("pay_failed_1"))
				.thenReturn(new PaymentGateway.ProviderPayment("failed", "order_rzp_" + orderId, 70000));

		mvc.perform(get("/api/payments").param("payment_id", "pay_failed_1").param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk());

		Order order = orderRepository.findById(orderId).orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.PENDING);
		mvc.perform(get("/api/orders/user").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void gatewayErrorDuringConfirmationIs500AndChangesNothing() throws Exception {
		String token = newUserToken();
		addToCart(token, createProduct("checkout_shirt", 1000, 700, "Blue", "M"), "M");
		long orderId = createOrder(token).get("id").asLong();
		when(paymentGateway.fetchPayment(eq("pay_boom"))).thenThrow(new RazorpayException("gateway down"));

		mvc.perform(get("/api/payments").param("payment_id", "pay_boom").param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isInternalServerError());

		assertThat(orderRepository.findById(orderId).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void reviewsAndRatingsAppearOnTheProduct() throws Exception {
		String token = newUserToken();
		Product product = createProduct("checkout_review", 900, 600, "Green", "S");

		mvc.perform(post("/api/reviews/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "review", "Great fit"))))
				.andExpect(status().isAccepted());
		mvc.perform(post("/api/ratings/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "rating", 4))))
				.andExpect(status().isAccepted());

		// product detail is public and must not fail once it has ratings (regression test)
		mvc.perform(get("/api/products/id/" + product.getId()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.ratings[0].rating").value(4.0))
				.andExpect(jsonPath("$.reviews[0].review").value("Great fit"))
				.andExpect(jsonPath("$.reviews[0].user.firstName").value("Test"))
				// reviewers' contact details and password hash are never exposed
				.andExpect(jsonPath("$.reviews[0].user.email").doesNotExist())
				.andExpect(jsonPath("$.reviews[0].user.password").doesNotExist())
				.andExpect(jsonPath("$.reviews[0].user.addresses").doesNotExist());

		mvc.perform(get("/api/reviews/product/" + product.getId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/ratings/product/" + product.getId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}
}
