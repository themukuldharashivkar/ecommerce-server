package com.bazaarly.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bazaarly.modal.Product;
import com.bazaarly.support.IntegrationTest;

class AdminAccessIntegrationTest extends IntegrationTest {

	private static final String NEW_PRODUCT = """
			{"title":"Admin Tee","description":"d","price":500,"discountedPrice":400,"discountPersent":20,
			 "quantity":5,"brand":"B","color":"Red","imageUrl":"https://example.com/x.jpg",
			 "size":[{"name":"M","quantity":5}],
			 "topLavelCategory":"Men","secondLavelCategory":"Clothing","thirdLavelCategory":"admin_test_tshirt"}""";

	/** Every admin endpoint, with a product and order id that exist. */
	private MockHttpServletRequestBuilder[] adminRequests(Long productId, Long orderId) {
		return new MockHttpServletRequestBuilder[] {
				get("/api/admin/orders/"),
				put("/api/admin/orders/" + orderId + "/confirmed"),
				put("/api/admin/orders/" + orderId + "/ship"),
				put("/api/admin/orders/" + orderId + "/deliver"),
				put("/api/admin/orders/" + orderId + "/cancel"),
				delete("/api/admin/orders/" + orderId + "/delete"),
				get("/api/admin/products/all"),
				post("/api/admin/products/").contentType(MediaType.APPLICATION_JSON).content(NEW_PRODUCT),
				post("/api/admin/products/creates").contentType(MediaType.APPLICATION_JSON).content("[" + NEW_PRODUCT + "]"),
				put("/api/admin/products/" + productId + "/update").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"),
				delete("/api/admin/products/" + productId + "/delete"),
		};
	}

	private Long placeOrder(String userToken, Long productId) throws Exception {
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", productId, "size", "M", "quantity", 1))))
				.andExpect(status().isAccepted());
		String order = mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"firstName\":\"A\",\"lastName\":\"B\",\"streetAddress\":\"1 St\",\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9999999999\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return readJson(order).get("id").asLong();
	}

	@Test
	void normalUserGets403OnEveryAdminEndpoint() throws Exception {
		String userToken = newUserToken();
		Product product = createProduct("admin_test_jeans", 1000, 800, "Blue", "M");
		Long orderId = placeOrder(userToken, product.getId());

		for (MockHttpServletRequestBuilder request : adminRequests(product.getId(), orderId)) {
			mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
					.andExpect(status().isForbidden());
		}
		// nothing was changed by the rejected calls (10 in stock, 1 reserved by the user's own order)
		mvc.perform(get("/api/products/id/" + product.getId()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.quantity").value(9));
		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.orderStatus").value("PENDING"));
	}

	@Test
	void readOnlyDemoAdminCanViewButNotChangeAnything() throws Exception {
		String viewer = signIn("viewer@test.local", "viewer-password-123"); // DEMO_ADMIN_* in the test profile
		String userToken = newUserToken();
		Product product = createProduct("admin_test_viewer", 1000, 800, "Blue", "M");
		Long orderId = placeOrder(userToken, product.getId());

		mvc.perform(get("/api/admin/orders/").header(HttpHeaders.AUTHORIZATION, bearer(viewer))).andExpect(status().isAccepted());
		mvc.perform(get("/api/admin/products/all").header(HttpHeaders.AUTHORIZATION, bearer(viewer))).andExpect(status().isOk());

		for (MockHttpServletRequestBuilder request : adminRequests(product.getId(), orderId)) {
			String method = request.buildRequest(new org.springframework.mock.web.MockServletContext()).getMethod();
			if (!"GET".equals(method)) {
				mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(viewer))).andExpect(status().isForbidden());
			}
		}
		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(jsonPath("$.orderStatus").value("PENDING"));
	}

	@Test
	void anonymousCallerGets401OnAdminEndpoints() throws Exception {
		mvc.perform(get("/api/admin/orders/")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/products/all")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/products/").contentType(MediaType.APPLICATION_JSON).content(NEW_PRODUCT))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void adminCanManageProducts() throws Exception {
		String admin = adminToken();

		String created = mvc.perform(post("/api/admin/products/").header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.contentType(MediaType.APPLICATION_JSON).content(NEW_PRODUCT))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.title").value("Admin Tee"))
				.andReturn().getResponse().getContentAsString();
		long productId = readJson(created).get("id").asLong();

		mvc.perform(put("/api/admin/products/" + productId + "/update").header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(3));

		mvc.perform(get("/api/admin/products/all").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk());

		mvc.perform(delete("/api/admin/products/" + productId + "/delete").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted());
		mvc.perform(get("/api/products/id/" + productId)).andExpect(status().isNotFound());
	}

	@Test
	void adminCanMoveAnOrderThroughFulfilment() throws Exception {
		String admin = adminToken();
		String userToken = newUserToken();
		Product product = createProduct("admin_test_kurta", 1200, 900, "White", "L");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "L", "quantity", 1))));
		Long orderId = placeOrderForCart(userToken);

		String[][] steps = { { "confirmed", "CONFIRMED" }, { "ship", "SHIPPED" }, { "deliver", "DELIVERED" } };
		for (String[] step : steps) {
			mvc.perform(put("/api/admin/orders/" + orderId + "/" + step[0]).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.orderStatus").value(step[1]));
		}
	}

	private Long placeOrderForCart(String userToken) throws Exception {
		String order = mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"firstName\":\"A\",\"lastName\":\"B\",\"streetAddress\":\"1 St\",\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9999999999\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return readJson(order).get("id").asLong();
	}

	@Test
	void signupCannotCreateAnAdmin() throws Exception {
		String email = uniqueEmail("wannabe");
		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"ROLE_ADMIN\"}"))
				.andExpect(status().isOk());
		String token = signIn(email, PASSWORD);

		mvc.perform(get("/api/admin/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isForbidden());
	}
}
