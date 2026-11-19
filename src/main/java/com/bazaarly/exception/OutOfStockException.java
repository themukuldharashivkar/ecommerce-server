package com.bazaarly.exception;

/** An order asked for more than is left. Answered with 409 Conflict, naming the product, size and what is left. */
public class OutOfStockException extends OrderException {

	private final long productId;
	private final String size;
	private final int available;

	public OutOfStockException(String message, long productId, String size, int available) {
		super(message);
		this.productId = productId;
		this.size = size;
		this.available = Math.max(available, 0);
	}

	public long getProductId() { return productId; }
	public String getSize() { return size; }
	public int getAvailable() { return available; }
}
