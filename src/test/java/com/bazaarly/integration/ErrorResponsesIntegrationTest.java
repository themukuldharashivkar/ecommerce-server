package com.bazaarly.integration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bazaarly.support.IntegrationTest;

/** Shape and status of the errors produced by the global exception handler (GlobleException). */
class ErrorResponsesIntegrationTest extends IntegrationTest {

	private static final long MISSING_ID = 987_654_321L;

	/** Every handled error has the same body: error message, request description, timestamp. */
	private static void assertErrorShape(ResultActions result, String message, String uri) throws Exception {
		result.andExpect(jsonPath("$.error").value(message))
				.andExpect(jsonPath("$.details").value("uri=" + uri))
				.andExpect(jsonPath("$.timestamp").isNotEmpty());
	}

	@Test
	void unknownProductIs400WithErrorBody() throws Exception {
		ResultActions result = mvc.perform(get("/api/products/id/" + MISSING_ID)).andExpect(status().isBadRequest());
		assertErrorShape(result, "product not found with id " + MISSING_ID, "/api/products/id/" + MISSING_ID);
	}

	@Test
	void unknownOrderIs400WithErrorBody() throws Exception {
		String token = newUserToken();
		ResultActions result = mvc.perform(get("/api/orders/" + MISSING_ID).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
		assertErrorShape(result, "order not exist with id " + MISSING_ID, "/api/orders/" + MISSING_ID);
	}

	@Test
	void unknownCartItemIs400WithErrorBody() throws Exception {
		String token = newUserToken();
		ResultActions result = mvc.perform(put("/api/cart_items/" + MISSING_ID).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
				.andExpect(status().isBadRequest());
		assertErrorShape(result, "cartItem not found with id : " + MISSING_ID, "/api/cart_items/" + MISSING_ID);
	}

	@Test
	void addingAnUnknownProductToTheCartIs400() throws Exception {
		String token = newUserToken();
		ResultActions result = mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"productId\":" + MISSING_ID + ",\"size\":\"M\",\"quantity\":1}"))
				.andExpect(status().isBadRequest());
		assertErrorShape(result, "product not found with id " + MISSING_ID, "/api/cart/add");
	}

	@Test
	void paymentForAnUnknownOrderIs400AndNeverReachesTheGateway() throws Exception {
		String token = newUserToken();
		ResultActions result = mvc.perform(post("/api/payments/" + MISSING_ID).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
		assertErrorShape(result, "order not exist with id " + MISSING_ID, "/api/payments/" + MISSING_ID);
		org.mockito.Mockito.verifyNoInteractions(paymentGateway);
	}

	@Test
	void badCredentialsIs401WithErrorBody() throws Exception {
		ResultActions result = mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + uniqueEmail("ghost") + "\",\"password\":\"whatever\"}"))
				.andExpect(status().isUnauthorized());
		assertErrorShape(result, "Invalid username or password", "/auth/signin");
	}

	@Test
	void unexpectedErrorsAre500WithTheSameShape() throws Exception {
		String token = newUserToken();
		addProductAndOrder(token);
		when(paymentGateway.fetchPayment(anyString())).thenThrow(new IllegalStateException("provider exploded"));

		ResultActions result = mvc.perform(get("/api/payments").param("payment_id", "pay_x").param("order_id", String.valueOf(lastOrderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isInternalServerError());
		assertErrorShape(result, "provider exploded", "/api/payments");
	}

	private long lastOrderId;

	private void addProductAndOrder(String token) throws Exception {
		long productId = createProduct("errors_tee", 800, 600, "Grey", "M").getId();
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productId\":" + productId + ",\"size\":\"M\",\"quantity\":1}"));
		String order = mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"A\",\"lastName\":\"B\",\"streetAddress\":\"1\",\"city\":\"C\",\"state\":\"S\",\"zipCode\":\"1\",\"mobile\":\"1\"}"))
				.andReturn().getResponse().getContentAsString();
		lastOrderId = readJson(order).get("id").asLong();
	}
}
