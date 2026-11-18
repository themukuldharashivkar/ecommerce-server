package com.bazaarly.cache;

public final class CacheNames {

	/** Pages of GET /api/products, keyed by {@link CatalogQuery#cacheKey()}. */
	public static final String CATALOG = "catalog";

	/** GET /api/products/id/{id}, keyed by product id. */
	public static final String PRODUCT = "product";

	private CacheNames() {
	}
}
