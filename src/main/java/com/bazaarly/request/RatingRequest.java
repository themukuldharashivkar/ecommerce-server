package com.bazaarly.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class RatingRequest {

	@NotNull(message = "productId is required")
	private Long productId;
	@DecimalMin(value = "1", message = "Rating must be between 1 and 5")
	@DecimalMax(value = "5", message = "Rating must be between 1 and 5")
	private double rating;
	public Long getProductId() {
		return productId;
	}
	public void setProductId(Long productId) {
		this.productId = productId;
	}
	public double getRating() {
		return rating;
	}
	public void setRating(double rating) {
		this.rating = rating;
	}
	
	
}
