package com.bazaarly.exception;

/** A ProductException that means "does not exist (for this caller)"; answered with 404. */
public class ProductNotFoundException extends ProductException {

	public ProductNotFoundException(String message) {
		super(message);
	}
}
