package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bazaarly.modal.Product;
import com.bazaarly.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/** Catalog filtering and search against real PostgreSQL (the JPQL and indexes from Flyway). */
class ProductQueriesIntegrationTest extends IntegrationTest {

	private String category;

	@BeforeEach
	void createCatalog() throws Exception {
		// a category of its own per test, so other tests' products never interfere
		category = "q_" + UUID.randomUUID().toString().substring(0, 8);
		createProduct(category, 1000, 900, "Red", "M", "L");    // 10% off
		createProduct(category, 2000, 1000, "Blue", "S");       // 50% off
		createProduct(category, 3000, 2400, "red", "XL");       // 20% off
		createProduct(category, 500, 450, "Green", "M");        // 10% off
	}

	/** The catalog request the frontend sends; overrides replace defaults (pairs of name, value). */
	private MockHttpServletRequestBuilder catalog(String category, String... overrides) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("category", category);
		params.put("color", "");
		params.put("size", "");
		params.put("minPrice", "0");
		params.put("maxPrice", "100000");
		params.put("minDiscount", "0");
		params.put("stock", "");
		params.put("sort", "price_low");
		params.put("pageNumber", "0");
		params.put("pageSize", "10");
		for (int i = 0; i < overrides.length; i += 2) {
			params.put(overrides[i], overrides[i + 1]);
		}
		MockHttpServletRequestBuilder request = get("/api/products");
		params.forEach(request::param);
		return request;
	}

	private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
		return readJson(mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
	}

	private static List<Integer> prices(JsonNode page) {
		List<Integer> prices = new ArrayList<>();
		page.get("content").forEach(p -> prices.add(p.get("discountedPrice").asInt()));
		return prices;
	}

	@Test
	void filtersByCategoryAndSortsByPrice() throws Exception {
		JsonNode page = page(catalog(category));
		assertThat(page.get("totalElements").asInt()).isEqualTo(4);
		assertThat(prices(page)).containsExactly(450, 900, 1000, 2400);

		JsonNode desc = page(catalog(category, "sort", "price_high"));
		assertThat(prices(desc)).containsExactly(2400, 1000, 900, 450);
	}

	@Test
	void categoryMatchIsCaseInsensitive() throws Exception {
		assertThat(page(catalog(category.toUpperCase())).get("totalElements").asInt()).isEqualTo(4);
	}

	@Test
	void filtersByPriceRangeAndMinimumDiscount() throws Exception {
		assertThat(prices(page(catalog(category, "minPrice", "800", "maxPrice", "1500"))))
				.containsExactly(900, 1000);
		assertThat(prices(page(catalog(category, "minDiscount", "20")))).containsExactly(1000, 2400);
	}

	@Test
	void filtersByColourIgnoringCase() throws Exception {
		assertThat(prices(page(catalog(category, "color", "red")))).containsExactly(900, 2400);
	}

	@Test
	void filtersBySize() throws Exception {
		assertThat(prices(page(catalog(category, "size", "M")))).containsExactly(450, 900);
	}

	@Test
	void pagesTheResult() throws Exception {
		JsonNode first = page(catalog(category, "pageSize", "3"));
		JsonNode second = page(catalog(category, "pageSize", "3", "pageNumber", "1"));
		assertThat(first.get("totalElements").asInt()).isEqualTo(4);
		assertThat(first.get("totalPages").asInt()).isEqualTo(2);
		assertThat(prices(first)).containsExactly(450, 900, 1000);
		assertThat(prices(second)).containsExactly(2400);
	}

	@Test
	void searchMatchesTitleBrandOrCategoryIgnoringCase() throws Exception {
		Product product = createProduct(category, 700, 650, "Black", "M");
		String titleWord = product.getTitle().substring("Product ".length(), "Product ".length() + 8);

		JsonNode byTitle = readJson(mvc.perform(get("/api/products/search").param("q", titleWord.toUpperCase()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(byTitle).hasSize(1);
		assertThat(byTitle.get(0).get("id").asLong()).isEqualTo(product.getId());

		JsonNode byCategory = readJson(mvc.perform(get("/api/products/search").param("q", category))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(byCategory).hasSize(5);

		JsonNode none = readJson(mvc.perform(get("/api/products/search").param("q", "no-such-product-" + UUID.randomUUID()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(none).isEmpty();
	}
}
