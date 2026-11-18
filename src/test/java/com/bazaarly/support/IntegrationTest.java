package com.bazaarly.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import com.bazaarly.modal.Product;
import com.bazaarly.modal.Size;
import com.bazaarly.notification.OrderConfirmationSender;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.request.CreateProductRequest;
import com.bazaarly.service.ProductService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for integration tests: the full Spring context, a real PostgreSQL in Docker
 * (one container shared by every test class, schema built by the real Flyway migrations)
 * and a mocked payment gateway, so Razorpay is never called.
 *
 * Tests share the database, so each test creates its own users and products with unique names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

	protected static final String ADMIN_EMAIL = "admin@test.local";
	protected static final String ADMIN_PASSWORD = "admin-password-123";
	protected static final String PASSWORD = "Passw0rd!";

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		// started once for the whole test run; Testcontainers' Ryuk removes it afterwards
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected ObjectMapper json;

	@Autowired
	protected ProductService productService;

	@MockBean
	protected PaymentGateway paymentGateway;

	/** Mocked so tests can see (and fail) deliveries; declared here so all tests share one Spring context. */
	@MockBean
	protected OrderConfirmationSender confirmationSender;

	protected static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@test.local";
	}

	protected static String bearer(String token) {
		return "Bearer " + token;
	}

	/** Signs up a new user and returns their JWT. */
	protected String signUp(String email) throws Exception {
		String body = json.writeValueAsString(Map.of("firstName", "Test", "lastName", "User", "email", email, "password", PASSWORD));
		String response = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("jwt").asText();
	}

	protected String newUserToken() throws Exception {
		return signUp(uniqueEmail("user"));
	}

	protected String signIn(String email, String password) throws Exception {
		String body = json.writeValueAsString(Map.of("email", email, "password", password));
		String response = mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("jwt").asText();
	}

	/** The admin account is created on startup from ADMIN_EMAIL / ADMIN_PASSWORD in the test profile. */
	protected String adminToken() throws Exception {
		return signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
	}

	protected Product createProduct(String category, int price, int discountedPrice, String color, String... sizes) throws Exception {
		CreateProductRequest req = new CreateProductRequest();
		req.setTitle("Product " + UUID.randomUUID());
		req.setDescription("integration test product");
		req.setBrand("TestBrand");
		req.setColor(color);
		req.setPrice(price);
		req.setDiscountedPrice(discountedPrice);
		req.setDiscountPersent(price == 0 ? 0 : (price - discountedPrice) * 100 / price);
		req.setQuantity(10);
		req.setImageUrl("https://example.com/p.jpg");
		req.setTopLavelCategory("Men");
		req.setSecondLavelCategory("Clothing");
		req.setThirdLavelCategory(category);
		Set<Size> sizeSet = new java.util.HashSet<>();
		for (String name : sizes) {
			Size size = new Size();
			size.setName(name);
			size.setQuantity(5);
			sizeSet.add(size);
		}
		req.setSize(sizeSet);
		return productService.createProduct(req);
	}

	protected JsonNode readJson(String body) throws Exception {
		return json.readTree(body);
	}
}
