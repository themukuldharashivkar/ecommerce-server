package com.bazaarly.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateCartItemRequest(
		@Min(value = 1, message = "Quantity must be at least 1")
		@Max(value = 10, message = "You can add at most 10 of an item") int quantity) {
}
