package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bazaarly.modal.Product;
import com.bazaarly.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** Request validation: invalid input gets 400 with a message per field, and nothing is changed. */
class ValidationIntegrationTest extends IntegrationTest {

	private static final String ADDRESS = "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\","
			+ "\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}";

	private ResultActions signup(Map<String, Object> body) throws Exception {
		return mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
	}

	private static ResultActions expectFieldError(ResultActions result, String field, String message) throws Exception {
		return result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation failed"))
				.andExpect(jsonPath("$.fieldErrors." + field).value(message));
	}

	// ---------- signup ----------

	@Test
	void weakPasswordsAreRejected() throws Exception {
		expectFieldError(signup(Map.of("firstName", "A", "lastName", "B", "email", uniqueEmail("pw"), "password", "short1")),
				"password", "Password must be 8 to 72 characters");
		expectFieldError(signup(Map.of("firstName", "A", "lastName", "B", "email", uniqueEmail("pw"), "password", "onlyletters")),
				"password", "Password must contain at least one letter and one number");
		expectFieldError(signup(Map.of("firstName", "A", "lastName", "B", "email", uniqueEmail("pw"), "password", "12345678")),
				"password", "Password must contain at least one letter and one number");
	}

	@Test
	void invalidEmailAndMissingNamesAreRejected() throws Exception {
		ResultActions result = signup(Map.of("email", "not-an-email", "password", PASSWORD));
		expectFieldError(result, "email", "Enter a valid email address")
				.andExpect(jsonPath("$.fieldErrors.firstName").value("First name is required"))
				.andExpect(jsonPath("$.fieldErrors.lastName").value("Last name is required"));
	}

	@Test
	void emailIsCaseInsensitive() throws Exception {
		String email = uniqueEmail("MiXeD").replace("mixed", "MiXeD");
		signUp(email);
		signIn(email.toUpperCase(), PASSWORD); // stored lower-cased, so any case signs in
		signup(Map.of("firstName", "A", "lastName", "B", "email", email.toLowerCase(), "password", PASSWORD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Email Is Already Used With Another Account"));
	}

	@Test
	void signinNeedsEmailAndPassword() throws Exception {
		expectFieldError(mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}")),
				"password", "Password is required");
	}

	// ---------- cart, reviews, ratings ----------

	@Test
	void cartQuantityAndSizeAreValidated() throws Exception {
		String token = newUserToken();
		Product product = createProduct("validation_tee", 1000, 800, "Red", "M");
		for (int quantity : new int[] { 0, 11 }) {
			mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
							.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", quantity))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.quantity").exists());
		}
		expectFieldError(mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "quantity", 1)))),
				"size", "Choose a size");
		mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.cartItems.length()").value(0));
	}

	@Test
	void theStorefrontsAddToCartPayloadWithoutQuantityAddsOne() throws Exception {
		// exactly what ProductDetails.jsx sends: { productId, size }
		String token = newUserToken();
		Product product = createProduct("validation_tee", 1000, 800, "Red", "M");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M"))))
				.andExpect(status().isAccepted());
		mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.cartItems[0].quantity").value(1));
	}

	@Test
	void phoneNumbersWithSpacesDashesAndBracketsAreAccepted() throws Exception {
		String token = newUserToken();
		Product product = createProduct("validation_phone", 1000, 800, "Red", "M");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M"))));
		mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content(ADDRESS.replace("9876543210", "+91 (98765) 43-210")))
				.andExpect(status().isOk());
	}

	@Test
	void addingThreeOfAnItemKeepsTheQuantity() throws Exception {
		// regression: the quantity used to be stored as 1
		String token = newUserToken();
		Product product = createProduct("validation_tee", 1000, 800, "Red", "M");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", 3))))
				.andExpect(status().isAccepted());
		mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.cartItems[0].quantity").value(3))
				.andExpect(jsonPath("$.totalDiscountedPrice").value(2400));
	}

	@Test
	void updatingACartItemValidatesTheQuantity() throws Exception {
		String token = newUserToken();
		Product product = createProduct("validation_tee", 1000, 800, "Red", "M");
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", 1))));
		long itemId = readJson(mvc.perform(get("/api/cart/").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andReturn().getResponse().getContentAsString()).get("cartItems").get(0).get("id").asLong();

		expectFieldError(mvc.perform(put("/api/cart_items/" + itemId).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}")),
				"quantity", "Quantity must be at least 1");
	}

	@Test
	void reviewsAndRatingsAreValidated() throws Exception {
		String token = newUserToken();
		Product product = createProduct("validation_review", 900, 600, "Green", "S");

		expectFieldError(mvc.perform(post("/api/reviews/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "review", "x".repeat(256))))),
				"review", "Reviews can be at most 255 characters");
		expectFieldError(mvc.perform(post("/api/ratings/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "rating", 6)))),
				"rating", "Rating must be between 1 and 5");
		mvc.perform(get("/api/reviews/product/" + product.getId())).andExpect(jsonPath("$.length()").value(0));
	}

	// ---------- admin products ----------

	@Test
	void invalidProductsAreRejected() throws Exception {
		String admin = adminToken();
		String bad = """
				{"title":"","price":500,"discountedPrice":600,"discountPersent":150,"quantity":-1,
				 "imageUrl":"ftp://x","size":[{"name":"","quantity":1}],
				 "topLavelCategory":"Men","secondLavelCategory":"Clothing","thirdLavelCategory":"t"}""";

		JsonNode errors = readJson(mvc.perform(post("/api/admin/products/").header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString()).get("fieldErrors");

		assertThat(errors.get("title").asText()).isEqualTo("Title is required");
		assertThat(errors.get("discountNotAbovePrice").asText()).isEqualTo("Discounted price cannot be higher than the price");
		assertThat(errors.get("discountPersent")).isNotNull();
		assertThat(errors.get("quantity").asText()).isEqualTo("Quantity cannot be negative");
		assertThat(errors.get("imageUrl").asText()).isEqualTo("Image URL must start with http:// or https://");
		assertThat(errors.has("size[].name")).isTrue();
	}

	@Test
	void bulkCreateRejectsTheWholeBatchIfOneProductIsInvalid() throws Exception {
		String valid = "{\"title\":\"Bulk ok\",\"price\":500,\"discountedPrice\":400,\"discountPersent\":20,\"quantity\":5,"
				+ "\"imageUrl\":\"https://example.com/x.jpg\",\"topLavelCategory\":\"Men\",\"secondLavelCategory\":\"Clothing\",\"thirdLavelCategory\":\"bulk_validation\"}";
		String invalid = valid.replace("\"title\":\"Bulk ok\"", "\"title\":\"\"");

		mvc.perform(post("/api/admin/products/creates").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content("[" + valid + "," + invalid + "]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation failed"));
		mvc.perform(get("/api/products/search").param("q", "Bulk ok")).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void adminCanMarkAProductSoldOut() throws Exception {
		Product product = createProduct("validation_soldout", 1000, 800, "Red", "M");
		mvc.perform(put("/api/admin/products/" + product.getId() + "/update").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(0));
	}

	// ---------- checkout ----------

	@Test
	void incompleteAddressIsRejected() throws Exception {
		String token = newUserToken();
		mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"Asha\",\"zipCode\":\"1\",\"mobile\":\"abc\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.complete").value("Name, street, city, state, postal code and phone are required"))
				.andExpect(jsonPath("$.fieldErrors.zipCode").value("Enter a valid postal code"))
				.andExpect(jsonPath("$.fieldErrors.mobile").value("Enter a valid phone number"));
	}

	@Test
	void orderFromAnEmptyCartIsRejected() throws Exception {
		mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(newUserToken()))
						.contentType(MediaType.APPLICATION_JSON).content(ADDRESS))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Your cart is empty"));
	}

	@Test
	void savedAddressCanBeReusedOnlyByItsOwner() throws Exception {
		Product product = createProduct("validation_addr", 1000, 800, "Red", "M");
		String owner = newUserToken();
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", 1))));
		long addressId = readJson(mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content(ADDRESS))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("shippingAddress").get("id").asLong();

		// the owner can check out again with the saved address, without creating a new one
		mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"id\":" + addressId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shippingAddress.id").value(addressId));
		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
				.andExpect(jsonPath("$.addresses.length()").value(1));

		// another user cannot use (or take over) that address
		String other = newUserToken();
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(other)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", 1))));
		mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(other)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"id\":" + addressId + ",\"firstName\":\"Mallory\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Saved address not found"));
		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
				.andExpect(jsonPath("$.addresses[0].firstName").value("Asha"));
	}
}
