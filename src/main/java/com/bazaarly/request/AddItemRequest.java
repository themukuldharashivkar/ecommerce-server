package com.bazaarly.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AddItemRequest {

	@NotNull(message = "productId is required")
	private Long productId;
	@NotBlank(message = "Choose a size")
	@Size(max = 20)
	private String size;
	@Min(value = 1, message = "Quantity must be at least 1")
	@Max(value = 10, message = "You can add at most 10 of an item")
	private int quantity;
	private Integer price;
	
	public AddItemRequest() {
		
	}
	public Long getProductId() {
		return productId;
	}
	public void setProductId(Long productId) {
		this.productId = productId;
	}
	public String getSize() {
		return size;
	}
	public void setSize(String size) {
		this.size = size;
	}
	public int getQuantity() {
		return quantity;
	}
	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}
	public Integer getPrice() {
		return price;
	}
	public void setPrice(Integer price) {
		this.price = price;
	}
	
	
}
