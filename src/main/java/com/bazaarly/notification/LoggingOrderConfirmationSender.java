package com.bazaarly.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when no mail server is configured (MAIL_ENABLED=false): records the confirmation in the log. */
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingOrderConfirmationSender implements OrderConfirmationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingOrderConfirmationSender.class);

	@Override
	public void send(OrderConfirmationMessage message) {
		// the address is personal data: log the order, not the recipient
		log.info("Order confirmation for order {} (mail disabled, not sent): {}", message.orderId(), message.subject());
	}
}
