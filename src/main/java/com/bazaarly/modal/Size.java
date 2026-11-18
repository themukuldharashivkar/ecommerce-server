package com.bazaarly.modal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;


public class Size {

	@NotBlank(message = "Size name is required")
	private String name;
	@Min(value = 0, message = "Size quantity cannot be negative")
	private int quantity;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public int getQuantity() {
		return quantity;
	}
	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}
	
	
}
