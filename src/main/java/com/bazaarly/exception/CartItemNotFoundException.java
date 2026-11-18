package com.bazaarly.exception;

/** A CartItemException that means "does not exist (for this caller)"; answered with 404. */
public class CartItemNotFoundException extends CartItemException {

	public CartItemNotFoundException(String message) {
		super(message);
	}
}
