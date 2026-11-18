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
	
	

	/*
	 * Value equality is required for an @ElementCollection of embeddables: without it Hibernate cannot
	 * match the loaded elements against its snapshot, treats the collection as changed on every flush
	 * and rewrites the product_sizes rows from (possibly stale) memory, which corrupted stock updated in SQL.
	 * It also gives the sizes a stable order in responses.
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Size other)) {
			return false;
		}
		return quantity == other.quantity && java.util.Objects.equals(name, other.name);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(name, quantity);
	}
}
