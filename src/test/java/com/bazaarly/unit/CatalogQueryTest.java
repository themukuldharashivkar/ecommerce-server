package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bazaarly.cache.CatalogQuery;

class CatalogQueryTest {

	private static CatalogQuery query(String category, List<String> colors, List<String> sizes, String sort, int page) {
		return new CatalogQuery(category, colors, sizes, 0, 100000, 0, sort, "", page, 10);
	}

	@Test
	void equivalentRequestsGetTheSameKey() {
		assertThat(query("Men_Jeans", List.of("Blue", "red"), List.of("M", "l"), "price_low", 0).cacheKey())
				.isEqualTo(query(" men_jeans ", List.of("RED", "blue", "blue"), List.of("L", "m"), "price_low", 0).cacheKey());
	}

	@Test
	void emptyValuesAreIgnoredInLists() {
		assertThat(query("saree", List.of(""), List.of(), "price_low", 0).cacheKey())
				.isEqualTo(query("saree", List.of(), null, "price_low", 0).cacheKey());
	}

	@Test
	void differentQueriesGetDifferentKeys() {
		String base = query("saree", List.of(), List.of(), "price_low", 0).cacheKey();
		assertThat(query("saree", List.of(), List.of(), "price_high", 0).cacheKey()).isNotEqualTo(base);
		assertThat(query("saree", List.of(), List.of(), "price_low", 1).cacheKey()).isNotEqualTo(base);
		assertThat(query("saree", List.of("red"), List.of(), "price_low", 0).cacheKey()).isNotEqualTo(base);
		assertThat(query("men_jeans", List.of(), List.of(), "price_low", 0).cacheKey()).isNotEqualTo(base);
	}

	@Test
	void sortIsKeptAsGivenBecauseTheServiceComparesItExactly() {
		assertThat(query("saree", List.of(), List.of(), "PRICE_LOW", 0).cacheKey())
				.isNotEqualTo(query("saree", List.of(), List.of(), "price_low", 0).cacheKey());
	}
}
