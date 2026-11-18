package com.bazaarly.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Admin product update; null fields are left unchanged. */
public record UpdateProductRequest(
		@Min(value = 0, message = "Quantity cannot be negative") Integer quantity,
		@Size(max = 255) String description) {
}
