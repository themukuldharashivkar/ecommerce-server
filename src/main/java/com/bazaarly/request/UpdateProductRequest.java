package com.bazaarly.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin product update. Every field is optional: null (or absent) leaves it unchanged.
 * Category and sizes are not edited here. The rule "discounted price <= price" is checked
 * on the result after merging with the stored product.
 */
public record UpdateProductRequest(
		@Size(min = 1, max = 255, message = "Title must be 1 to 255 characters") String title,
		@Size(max = 255) String description,
		@Size(max = 255) String brand,
		@Size(max = 255) String color,
		@Size(max = 255) @Pattern(regexp = "https?://.+", message = "Image URL must start with http:// or https://") String imageUrl,
		@Min(value = 1, message = "Price must be at least 1") Integer price,
		@Min(value = 1, message = "Discounted price must be at least 1") Integer discountedPrice,
		@Min(0) @Max(100) Integer discountPersent,
		@Min(value = 0, message = "Quantity cannot be negative") Integer quantity) {

	/** Only quantity and/or description (the original admin update). */
	public static UpdateProductRequest of(Integer quantity, String description) {
		return new UpdateProductRequest(null, description, null, null, null, null, null, null, quantity);
	}
}
