package com.bazaarly.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.bazaarly.modal.Product;
import com.bazaarly.modal.Size;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Catalog filters, all evaluated by the database so it can use the indexes and page with LIMIT/OFFSET
 * (previously every product of the category was loaded and filtered in Java).
 * Empty or null filters are ignored. Text filters are case-insensitive.
 */
public final class ProductSpecifications {

	private ProductSpecifications() {
	}

	public static Specification<Product> catalog(String category, List<String> colors, List<String> sizes,
			Integer minPrice, Integer maxPrice, Integer minDiscount, String stock) {
		List<String> colorValues = lowerNonBlank(colors);
		List<String> sizeValues = lowerNonBlank(sizes);

		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (category != null && !category.isBlank()) {
				where.add(cb.equal(cb.lower(root.join("category").get("name")), category.trim().toLowerCase(Locale.ROOT)));
			}
			if (minPrice != null) {
				where.add(cb.greaterThanOrEqualTo(root.get("discountedPrice"), minPrice));
			}
			if (maxPrice != null) {
				where.add(cb.lessThanOrEqualTo(root.get("discountedPrice"), maxPrice));
			}
			if (minDiscount != null) {
				where.add(cb.greaterThanOrEqualTo(root.get("discountPersent"), minDiscount));
			}
			if (!colorValues.isEmpty()) {
				where.add(cb.lower(root.get("color")).in(colorValues));
			}
			if (!sizeValues.isEmpty()) {
				// at least one of the requested sizes is in stock
				Subquery<Long> inStockSize = query.subquery(Long.class);
				Root<Product> product = inStockSize.from(Product.class);
				Join<Product, Size> size = product.join("sizes");
				inStockSize.select(product.get("id")).where(
						cb.equal(product, root),
						cb.lower(size.get("name")).in(sizeValues),
						cb.greaterThan(size.get("quantity"), 0));
				where.add(cb.exists(inStockSize));
			}
			if ("in_stock".equals(stock)) {
				where.add(cb.greaterThan(root.get("quantity"), 0));
			} else if ("out_of_stock".equals(stock)) {
				where.add(cb.lessThan(root.get("quantity"), 1));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

	/** price_low / price_high sort by the price shown (discounted price); ties and other values by id. */
	public static Sort catalogSort(String sort) {
		if ("price_low".equals(sort)) {
			return Sort.by(Sort.Order.asc("discountedPrice"), Sort.Order.asc("id"));
		}
		if ("price_high".equals(sort)) {
			return Sort.by(Sort.Order.desc("discountedPrice"), Sort.Order.asc("id"));
		}
		return Sort.by(Sort.Order.asc("id"));
	}

	private static List<String> lowerNonBlank(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
	}
}
