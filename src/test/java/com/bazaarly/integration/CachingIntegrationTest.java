package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

import com.bazaarly.modal.Product;
import com.bazaarly.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Cache hits and evictions against a real Redis (Testcontainers).
 *
 * To prove a response came from the cache, the test changes the database directly with JDBC
 * (bypassing the application, so nothing is evicted) and checks the API still returns the old data.
 * A change made through the application must then be visible straight away.
 */
@TestPropertySource(properties = "spring.cache.type=redis")
class CachingIntegrationTest extends IntegrationTest {

	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redis(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired CacheManager cacheManager;

	String category;
	Product product;

	@BeforeEach
	void setUp() throws Exception {
		category = "cache_" + UUID.randomUUID().toString().substring(0, 8);
		product = createProduct(category, 1000, 800, "Blue", "M");
	}

	private MockHttpServletRequestBuilder catalogRequest() {
		return get("/api/products").param("category", category).param("color", "").param("size", "")
				.param("minPrice", "0").param("maxPrice", "100000").param("minDiscount", "0")
				.param("stock", "").param("sort", "price_low").param("pageNumber", "0").param("pageSize", "10");
	}

	private String body(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
	}

	private JsonNode detail() throws Exception {
		return readJson(body(get("/api/products/id/" + product.getId())));
	}

	private void changeQuantityBehindTheAppsBack(int quantity) {
		jdbc.update("update product set quantity = ? where id = ?", quantity, product.getId());
	}

	@Test
	void usesRedis() {
		assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
	}

	@Test
	void productDetailIsServedFromRedisUntilAnAdminUpdateEvictsIt() throws Exception {
		String first = body(get("/api/products/id/" + product.getId()));
		assertThat(redis.hasKey("bazaarly:product::" + product.getId())).isTrue();

		changeQuantityBehindTheAppsBack(99);
		String second = body(get("/api/products/id/" + product.getId()));
		assertThat(second).isEqualTo(first);                       // cache hit: same bytes, stale quantity
		assertThat(readJson(second).get("quantity").asInt()).isEqualTo(10);

		mvc.perform(put("/api/admin/products/" + product.getId() + "/update")
						.header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
				.andExpect(status().isOk());

		assertThat(detail().get("quantity").asInt()).isEqualTo(5);  // evicted: fresh data, no stale read
	}

	@Test
	void catalogPageIsServedFromRedisUntilANewProductEvictsIt() throws Exception {
		String first = body(catalogRequest());
		assertThat(readJson(first).get("totalElements").asInt()).isEqualTo(1);

		jdbc.update("update product set discounted_price = 1 where id = ?", product.getId());
		assertThat(body(catalogRequest())).isEqualTo(first);       // cache hit

		createProduct(category, 2000, 1500, "Red", "L");            // through the service: evicts the catalog
		JsonNode fresh = readJson(body(catalogRequest()));
		assertThat(fresh.get("totalElements").asInt()).isEqualTo(2);
		assertThat(fresh.get("content").get(0).get("discountedPrice").asInt()).isEqualTo(1); // DB change now visible
	}

	@Test
	void newReviewAndRatingEvictTheProduct() throws Exception {
		assertThat(detail().get("reviews")).isEmpty();
		String user = newUserToken();

		mvc.perform(post("/api/reviews/create").header(HttpHeaders.AUTHORIZATION, bearer(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "review", "Lovely"))))
				.andExpect(status().isAccepted());
		assertThat(detail().get("reviews")).hasSize(1);

		mvc.perform(post("/api/ratings/create").header(HttpHeaders.AUTHORIZATION, bearer(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("productId", product.getId(), "rating", 5))))
				.andExpect(status().isAccepted());
		assertThat(detail().get("ratings")).hasSize(1);
		assertThat(readJson(body(catalogRequest())).get("content").get(0).get("ratings")).hasSize(1);
	}

	@Test
	void deletedProductIsNotServedFromTheCache() throws Exception {
		detail(); // cached
		mvc.perform(delete("/api/admin/products/" + product.getId() + "/delete")
						.header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
				.andExpect(status().isAccepted());

		mvc.perform(get("/api/products/id/" + product.getId())).andExpect(status().isBadRequest());
		assertThat(redis.hasKey("bazaarly:product::" + product.getId())).isFalse();
	}

	@Test
	void errorsAreNotCached() throws Exception {
		long missing = 900_000_000L + (long) (Math.random() * 1000);
		mvc.perform(get("/api/products/id/" + missing)).andExpect(status().isBadRequest());
		assertThat(redis.hasKey("bazaarly:product::" + missing)).isFalse();
	}

	@Test
	void entriesHaveTheConfiguredTtl() throws Exception {
		detail();
		body(catalogRequest());

		Long productTtl = redis.getExpire("bazaarly:product::" + product.getId());
		Long catalogTtl = redis.getExpire(redis.keys("bazaarly:catalog::" + category + "*").iterator().next());
		assertThat(productTtl).isBetween(1L, 30 * 60L);  // PT30M
		assertThat(catalogTtl).isBetween(1L, 10 * 60L);  // PT10M
	}

	@Test
	void equivalentCatalogRequestsShareOneEntry() throws Exception {
		// colour and size are matched case-insensitively and in any order, so these are the same query
		body(catalogRequestWithColor("Blue,red"));
		body(catalogRequestWithColor("RED,blue"));
		assertThat(redis.keys("bazaarly:catalog::" + category + "*")).hasSize(1);

		body(catalogRequestWithColor("green"));
		assertThat(redis.keys("bazaarly:catalog::" + category + "*")).hasSize(2);
	}

	private MockHttpServletRequestBuilder catalogRequestWithColor(String color) {
		return get("/api/products").param("category", category).param("color", color).param("size", "")
				.param("minPrice", "0").param("maxPrice", "100000").param("minDiscount", "0")
				.param("stock", "").param("sort", "price_low").param("pageNumber", "0").param("pageSize", "10");
	}
}
