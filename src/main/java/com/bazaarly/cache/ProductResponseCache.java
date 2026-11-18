package com.bazaarly.cache;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bazaarly.exception.ProductException;
import com.bazaarly.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-through cache for the public catalog endpoints.
 *
 * The cached value is the finished JSON response, produced by the application's own ObjectMapper.
 * Caching entities instead would mean serialising Hibernate lazy collections and Spring Data's PageImpl
 * into Redis and back, which is fragile; caching the JSON makes a hit byte-for-byte identical to a miss
 * and costs no database work or serialisation.
 *
 * Entries are evicted by ProductServiceImplementation, ReviewServiceImplementation and
 * RatingServiceImplementation whenever a product, review or rating changes. TTLs are a safety net.
 * Exceptions (e.g. unknown product) are never cached.
 */
@Service
public class ProductResponseCache {

	private final ProductService productService;
	private final ObjectMapper objectMapper;

	public ProductResponseCache(ProductService productService, ObjectMapper objectMapper) {
		this.productService = productService;
		this.objectMapper = objectMapper;
	}

	// readOnly transaction keeps the session open while lazy collections are serialised
	@Cacheable(cacheNames = CacheNames.CATALOG, key = "#query.cacheKey()")
	@Transactional(readOnly = true)
	public String catalogPage(CatalogQuery query) throws JsonProcessingException {
		return objectMapper.writeValueAsString(productService.getAllProduct(query.category(), query.colors(),
				query.sizes(), query.minPrice(), query.maxPrice(), query.minDiscount(), query.sort(), query.stock(),
				query.pageNumber(), query.pageSize()));
	}

	@Cacheable(cacheNames = CacheNames.PRODUCT, key = "#productId")
	@Transactional(readOnly = true)
	public String product(Long productId) throws ProductException, JsonProcessingException {
		return objectMapper.writeValueAsString(productService.findProductById(productId));
	}
}
