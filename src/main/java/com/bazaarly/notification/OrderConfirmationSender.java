package com.bazaarly.notification;

/** Delivers an order confirmation. Throwing marks the notification FAILED so it is retried. */
public interface OrderConfirmationSender {

	void send(OrderConfirmationMessage message) throws Exception;
}
