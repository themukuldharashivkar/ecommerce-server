package com.bazaarly.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Checkout address. With an id, the caller's saved address with that id is reused (the other fields are
 * ignored); without one, the fields are required and a new address is saved.
 */
public record ShippingAddressRequest(
		Long id,
		@Size(max = 100) String firstName,
		@Size(max = 100) String lastName,
		@Size(max = 255) String streetAddress,
		@Size(max = 100) String city,
		@Size(max = 100) String state,
		@Pattern(regexp = "^$|^[A-Za-z0-9 -]{3,10}$", message = "Enter a valid postal code") String zipCode,
		@Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,20}$", message = "Enter a valid phone number") String mobile) {

	@jakarta.validation.constraints.AssertTrue(message = "Name, street, city, state, postal code and phone are required")
	@com.fasterxml.jackson.annotation.JsonIgnore
	public boolean isComplete() {
		return id != null || (notBlank(firstName) && notBlank(lastName) && notBlank(streetAddress) && notBlank(city)
				&& notBlank(state) && notBlank(zipCode) && notBlank(mobile));
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
