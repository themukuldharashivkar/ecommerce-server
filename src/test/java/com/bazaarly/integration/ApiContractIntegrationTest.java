package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.bazaarly.modal.Product;
import com.bazaarly.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** Error handling, pagination, access rules, health, API docs and CORS. */
class ApiContractIntegrationTest extends IntegrationTest {

	// ---------- client errors are 4xx with the standard body, never 500 ----------

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON).content("{bad"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Malformed or missing request body"))
				.andExpect(jsonPath("$.details").value("uri=/auth/signin"));
	}

	@Test
	void missingAndWrongTypeParametersAre400() throws Exception {
		mvc.perform(get("/api/products/search")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Missing required parameter 'q'"));
		mvc.perform(get("/api/products/id/abc")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Invalid value for 'productId'"));
		mvc.perform(get("/api/products").param("pageSize", "lots")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownRouteIs404AndWrongMethodIs405() throws Exception {
		mvc.perform(get("/no/such/route")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Endpoint not found"));
		mvc.perform(delete("/auth/signin")).andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error").value("Method DELETE is not supported here"));
	}

	@Test
	void wrongContentTypeIs415() throws Exception {
		mvc.perform(post("/auth/signin").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void authErrorsUseTheSameBody() throws Exception {
		mvc.perform(get("/api/cart/")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Authentication required: send a valid Bearer token"))
				.andExpect(jsonPath("$.details").value("uri=/api/cart/"));
		mvc.perform(get("/api/admin/orders/").header(HttpHeaders.AUTHORIZATION, bearer(newUserToken())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("You do not have permission to do this"));
	}

	@Test
	void httpBasicAndTheLoginFormAreGone() throws Exception {
		String basic = Base64.getEncoder().encodeToString((ADMIN_EMAIL + ":" + ADMIN_PASSWORD).getBytes());
		mvc.perform(get("/api/admin/orders/").header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/login")).andExpect(status().isNotFound());
	}

	// ---------- access rules ----------

	@Test
	void ordersCanOnlyBeReadByTheirBuyer() throws Exception {
		String buyer = newUserToken();
		Product product = createProduct("contract_tee", 1000, 800, "Red", "M");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", 1))));
		long orderId = readJson(mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\",\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}"))
				.andReturn().getResponse().getContentAsString()).get("id").asLong();

		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isAccepted());
		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(newUserToken())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("order not exist with id " + orderId));
	}

	@Test
	void productReviewsAndRatingsArePublic() throws Exception {
		Product product = createProduct("contract_public", 900, 600, "Green", "S");
		mvc.perform(get("/api/reviews/product/" + product.getId())).andExpect(status().isOk());
		mvc.perform(get("/api/ratings/product/" + product.getId())).andExpect(status().isOk());
	}

	// ---------- pagination ----------

	@Test
	void catalogFiltersAreOptionalAndPageSizeIsCapped() throws Exception {
		JsonNode page = readJson(mvc.perform(get("/api/products")).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString());
		assertThat(page.get("size").asInt()).isEqualTo(12);
		assertThat(page.get("number").asInt()).isZero();

		mvc.perform(get("/api/products").param("pageSize", "51")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.pageSize").value("pageSize can be at most 50"));
		mvc.perform(get("/api/products").param("pageNumber", "-1")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/products").param("minDiscount", "101")).andExpect(status().isBadRequest());
	}

	@Test
	void searchIsPagedWithTotalInHeader() throws Exception {
		String word = "pg" + UUID.randomUUID().toString().substring(0, 6);
		for (int i = 0; i < 5; i++) {
			createProduct(word, 1000, 800, "Red", "M");
		}
		mvc.perform(get("/api/products/search").param("q", word).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(header().string("X-Total-Count", "5"));
		mvc.perform(get("/api/products/search").param("q", word).param("size", "2").param("page", "2"))
				.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/products/search").param("q", word).param("size", "51"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.size").value("size can be at most 50"));
	}

	@Test
	void listEndpointsAreCapped() throws Exception {
		createProduct("contract_list", 1000, 800, "Red", "M");
		createProduct("contract_list", 1000, 800, "Red", "M");
		String admin = adminToken();
		mvc.perform(get("/api/admin/orders/").param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/products/all").param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(header().exists("X-Total-Count"));
		mvc.perform(get("/api/orders/user").header(HttpHeaders.AUTHORIZATION, bearer(newUserToken())))
				.andExpect(status().isAccepted())
				.andExpect(header().string("X-Total-Count", "0"));
	}

	@Test
	void reviewsArePagedNewestFirst() throws Exception {
		Product product = createProduct("contract_reviews", 900, 600, "Green", "S");
		String token = newUserToken();
		for (String text : new String[] { "first", "second", "third" }) {
			mvc.perform(post("/api/reviews/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("productId", product.getId(), "review", text))));
		}
		mvc.perform(get("/api/reviews/product/" + product.getId()).param("size", "2"))
				.andExpect(header().string("X-Total-Count", "3"))
				.andExpect(jsonPath("$[0].review").value("third"))
				.andExpect(jsonPath("$[1].review").value("second"));
	}

	// ---------- health, docs, CORS ----------

	@Test
	void healthIsPublicAndHidesDetails() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/beans").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))).andExpect(status().isNotFound());
	}

	@Test
	void openApiDocumentListsTheEndpointsAndBearerAuth() throws Exception {
		JsonNode doc = readJson(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(doc.get("components").get("securitySchemes").get("bearerAuth").get("scheme").asText()).isEqualTo("bearer");
		JsonNode paths = doc.get("paths");
		for (String path : new String[] { "/auth/signin", "/auth/signup", "/api/products", "/api/products/id/{productId}",
				"/api/products/search", "/api/cart/", "/api/cart/add", "/api/orders/", "/api/orders/user", "/api/payments",
				"/api/payments/{orderId}", "/api/admin/orders/", "/api/admin/products/" }) {
			assertThat(paths.has(path)).as(path).isTrue();
		}
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void corsAllowsOnlyTheStorefrontAndExposesTheTotalCount() throws Exception {
		mvc.perform(options("/api/products").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
		mvc.perform(get("/api/products/search").param("q", "x").header("Origin", "http://localhost:3000"))
				.andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("X-Total-Count")));
		mvc.perform(options("/api/products").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden());
	}
}
