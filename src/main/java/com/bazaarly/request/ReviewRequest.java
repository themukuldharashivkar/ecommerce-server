package com.bazaarly.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReviewRequest {
	
	@NotNull(message = "productId is required")
	private Long productId;
	@NotBlank(message = "Review text is required")
	@Size(max = 255, message = "Reviews can be at most 255 characters")
	private String review;
	public Long getProductId() {
		return productId;
	}
	public void setProductId(Long productId) {
		this.productId = productId;
	}
	public String getReview() {
		return review;
	}
	public void setReview(String review) {
		this.review = review;
	}

	
}
