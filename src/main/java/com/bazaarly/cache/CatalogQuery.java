package com.bazaarly.cache;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The parameters of GET /api/products. The cache key is normalised so equivalent requests share an entry:
 * category, colour and size are matched case-insensitively and colour/size order does not matter.
 * Sort and stock are compared exactly by the service, so they stay as given.
 */
public record CatalogQuery(String category, List<String> colors, List<String> sizes, Integer minPrice,
		Integer maxPrice, Integer minDiscount, String sort, String stock, Integer pageNumber, Integer pageSize) {

	public String cacheKey() {
		return String.join("|",
				lower(category),
				normalise(colors),
				normalise(sizes),
				String.valueOf(minPrice),
				String.valueOf(maxPrice),
				String.valueOf(minDiscount),
				String.valueOf(sort),
				String.valueOf(stock),
				String.valueOf(pageNumber),
				String.valueOf(pageSize));
	}

	private static String lower(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalise(List<String> values) {
		if (values == null) {
			return "";
		}
		return values.stream().map(CatalogQuery::lower).filter(v -> !v.isEmpty()).sorted().distinct()
				.collect(Collectors.joining(","));
	}
}
