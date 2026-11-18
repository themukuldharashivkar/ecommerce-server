package com.bazaarly.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bazaarly.cache.CacheNames;
import com.bazaarly.exception.OrderException;
import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.OrderItem;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.Size;

/**
 * Product stock. An order reserves its items when it is placed and gives them back if it is cancelled
 * or expires unpaid.
 *
 * Each change is a conditional UPDATE ("... WHERE quantity >= n"), so the database refuses to go below
 * zero even when two orders for the last item arrive at the same moment. Both the product total and the
 * chosen size are updated. Callers run inside a transaction, so a failure on any line undoes the others.
 * The product and catalog caches are evicted after the transaction commits.
 */
@Service
public class StockService {

	private static final Logger log = LoggerFactory.getLogger(StockService.class);

	private final JdbcTemplate jdbc;
	private final CacheManager cacheManager;

	public StockService(JdbcTemplate jdbc, CacheManager cacheManager) {
		this.jdbc = jdbc;
		this.cacheManager = cacheManager;
	}

	/** Checks a cart line against current stock (no reservation yet). */
	public void checkAvailable(Product product, String size, int quantity) throws ProductException {
		if (!product.getSizes().isEmpty()) {
			Optional<Size> match = product.getSizes().stream()
					.filter(s -> s.getName() != null && s.getName().equalsIgnoreCase(size)).findFirst();
			if (match.isEmpty()) {
				throw new ProductException("Size " + size + " is not available for this product");
			}
			if (match.get().getQuantity() < quantity) {
				throw new ProductException(shortage(product.getTitle(), match.get().getName(), match.get().getQuantity()));
			}
		}
		if (product.getQuantity() < quantity) {
			throw new ProductException(shortage(product.getTitle(), size, product.getQuantity()));
		}
	}

	/** Takes the items out of stock, or throws without changing anything (the transaction rolls back). */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void reserve(List<OrderItem> items) throws OrderException {
		Set<Long> touched = new LinkedHashSet<>();
		for (OrderItem item : items) {
			long productId = item.getProduct().getId();
			int quantity = item.getQuantity();
			if (hasSizes(productId)) {
				int sizeRows = jdbc.update("""
						UPDATE product_sizes SET quantity = quantity - ?
						WHERE product_id = ? AND lower(name) = ? AND quantity >= ?""",
						quantity, productId, lower(item.getSize()), quantity);
				if (sizeRows != 1) {
					throw new OrderException(shortage(item.getProduct().getTitle(), item.getSize(), sizeLeft(productId, item.getSize())));
				}
			}
			int productRows = jdbc.update("UPDATE product SET quantity = quantity - ? WHERE id = ? AND quantity >= ?",
					quantity, productId, quantity);
			if (productRows != 1) {
				throw new OrderException(shortage(item.getProduct().getTitle(), item.getSize(), productLeft(productId)));
			}
			touched.add(productId);
		}
		evictAfterCommit(touched);
	}

	/** Puts the items back (order cancelled or expired). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(List<OrderItem> items) {
		Set<Long> touched = new LinkedHashSet<>();
		for (OrderItem item : items) {
			long productId = item.getProduct().getId();
			jdbc.update("UPDATE product_sizes SET quantity = quantity + ? WHERE product_id = ? AND lower(name) = ?",
					item.getQuantity(), productId, lower(item.getSize()));
			jdbc.update("UPDATE product SET quantity = quantity + ? WHERE id = ?", item.getQuantity(), productId);
			touched.add(productId);
		}
		evictAfterCommit(touched);
	}

	private boolean hasSizes(long productId) {
		Integer rows = jdbc.queryForObject("SELECT count(*) FROM product_sizes WHERE product_id = ?", Integer.class, productId);
		return rows != null && rows > 0;
	}

	private int sizeLeft(long productId, String size) {
		List<Integer> left = jdbc.queryForList("SELECT quantity FROM product_sizes WHERE product_id = ? AND lower(name) = ?",
				Integer.class, productId, lower(size));
		return left.isEmpty() ? 0 : left.get(0);
	}

	private int productLeft(long productId) {
		Integer left = jdbc.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
		return left == null ? 0 : left;
	}

	private static String shortage(String title, String size, int left) {
		String what = size == null || size.isBlank() ? title : title + " in size " + size;
		return left <= 0 ? what + " is sold out" : "Only " + left + " left of " + what;
	}

	private static String lower(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	/** Stock is part of the cached product JSON: drop those entries once the change is committed. */
	private void evictAfterCommit(Set<Long> productIds) {
		if (productIds.isEmpty()) {
			return;
		}
		Runnable evict = () -> {
			try {
				Cache products = cacheManager.getCache(CacheNames.PRODUCT);
				Cache catalog = cacheManager.getCache(CacheNames.CATALOG);
				if (products != null) {
					productIds.forEach(products::evict);
				}
				if (catalog != null) {
					catalog.clear();
				}
			} catch (RuntimeException e) {
				log.error("Cache eviction after stock change failed; entries may be stale until their TTL: {}", e.getMessage());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					evict.run();
				}
			});
		} else {
			evict.run();
		}
	}
}
