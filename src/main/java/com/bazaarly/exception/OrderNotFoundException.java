package com.bazaarly.exception;

/** A OrderException that means "does not exist (for this caller)"; answered with 404. */
public class OrderNotFoundException extends OrderException {

	public OrderNotFoundException(String message) {
		super(message);
	}
}
