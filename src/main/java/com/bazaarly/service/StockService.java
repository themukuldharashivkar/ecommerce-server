package com.bazaarly.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
import com.bazaarly.exception.OutOfStockException;
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
		for (Map.Entry<Long, List<OrderItem>> entry : inLockOrder(items).entrySet()) {
			long productId = entry.getKey();
			List<OrderItem> lines = entry.getValue();
			if (hasSizes(productId)) {
				for (OrderItem item : lines) {
					int sizeRows = jdbc.update("""
							UPDATE product_sizes SET quantity = quantity - ?
							WHERE product_id = ? AND lower(name) = ? AND quantity >= ?""",
							item.getQuantity(), productId, lower(item.getSize()), item.getQuantity());
					if (sizeRows != 1) {
						int left = sizeLeft(productId, item.getSize());
						throw new OutOfStockException(shortage(item.getProduct().getTitle(), item.getSize(), left), productId,
								item.getSize(), left);
					}
				}
			}
			int total = lines.stream().mapToInt(OrderItem::getQuantity).sum();
			int productRows = jdbc.update("UPDATE product SET quantity = quantity - ? WHERE id = ? AND quantity >= ?",
					total, productId, total);
			if (productRows != 1) {
				OrderItem first = lines.get(0);
				String size = lines.size() == 1 ? first.getSize() : null;
				int left = productLeft(productId);
				throw new OutOfStockException(shortage(first.getProduct().getTitle(), size, left), productId, size, left);
			}
		}
		evictAfterCommit(inLockOrder(items).keySet());
	}

	/** Puts the items back (order cancelled or expired). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(List<OrderItem> items) {
		for (Map.Entry<Long, List<OrderItem>> entry : inLockOrder(items).entrySet()) {
			long productId = entry.getKey();
			int total = 0;
			for (OrderItem item : entry.getValue()) {
				jdbc.update("UPDATE product_sizes SET quantity = quantity + ? WHERE product_id = ? AND lower(name) = ?",
						item.getQuantity(), productId, lower(item.getSize()));
				total += item.getQuantity();
			}
			jdbc.update("UPDATE product SET quantity = quantity + ? WHERE id = ?", total, productId);
		}
		evictAfterCommit(inLockOrder(items).keySet());
	}

	/**
	 * Each UPDATE locks the row it changes until the transaction ends. Two orders that take the same locks in
	 * different orders can wait for each other forever (PostgreSQL then aborts one with "deadlock detected").
	 * So every order locks in one global order: products by id, and for each product first its size rows
	 * (by size), then the product row, once, for the product's total. Locking the product row between two of
	 * its sizes would deadlock with an order for only the second size.
	 */
	private static Map<Long, List<OrderItem>> inLockOrder(List<OrderItem> items) {
		Map<Long, List<OrderItem>> byProduct = new TreeMap<>();
		items.stream()
				.sorted(Comparator.comparing((OrderItem item) -> lower(item.getSize())))
				.forEach(item -> byProduct.computeIfAbsent(item.getProduct().getId(), id -> new ArrayList<>()).add(item));
		return byProduct;
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
