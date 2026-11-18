package com.bazaarly.notification;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends order confirmations in the background.
 *
 * Flow: PaymentService marks the order paid and inserts a PENDING outbox row in one transaction, then
 * publishes OrderPaidEvent. After that transaction commits, {@link #onOrderPaid} runs on the bounded
 * "notificationExecutor" pool, so the payment response does not wait for the email.
 *
 * Every attempt first claims the row (PENDING/FAILED -> SENDING) with a conditional UPDATE, so a retried
 * confirmation, a second event, or the sweeper running at the same time can never send twice.
 * Failures are recorded on the row and retried by {@link #retryDue} with linear backoff, up to maxAttempts.
 */
@Component
public class OrderConfirmationJob {

	private static final Logger log = LoggerFactory.getLogger(OrderConfirmationJob.class);

	private final OrderNotificationStore store;
	private final OrderConfirmationMessageBuilder messageBuilder;
	private final OrderConfirmationSender sender;
	private final int maxAttempts;
	private final Duration retryBackoff;
	private final Duration pendingGrace;
	private final Duration sendingTimeout;

	public OrderConfirmationJob(OrderNotificationStore store, OrderConfirmationMessageBuilder messageBuilder,
			OrderConfirmationSender sender,
			@Value("${app.notifications.max-attempts}") int maxAttempts,
			@Value("${app.notifications.retry-backoff}") Duration retryBackoff,
			@Value("${app.notifications.pending-grace}") Duration pendingGrace,
			@Value("${app.notifications.sending-timeout}") Duration sendingTimeout) {
		this.store = store;
		this.messageBuilder = messageBuilder;
		this.sender = sender;
		this.maxAttempts = maxAttempts;
		this.retryBackoff = retryBackoff;
		this.pendingGrace = pendingGrace;
		this.sendingTimeout = sendingTimeout;
	}

	@Async(NotificationConfig.EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOrderPaid(OrderPaidEvent event) {
		process(event.orderId());
	}

	/** One delivery attempt. Safe to call any number of times for the same order. */
	public void process(long orderId) {
		if (!store.claim(orderId, maxAttempts)) {
			log.debug("Confirmation for order {} already sent, in progress or out of attempts", orderId);
			return;
		}
		try {
			sender.send(messageBuilder.build(orderId));
			store.markSent(orderId);
			log.info("Order confirmation sent for order {}", orderId);
		} catch (Exception e) {
			store.markFailed(orderId, e.getClass().getSimpleName() + ": " + e.getMessage());
			log.warn("Order confirmation for order {} failed, will retry: {}", orderId, e.getMessage());
		}
	}

	/** Picks up confirmations that failed or were never started (e.g. app restart, full queue). */
	@Scheduled(fixedDelayString = "${app.notifications.sweep-interval}",
			initialDelayString = "${app.notifications.sweep-interval}")
	public void retryDue() {
		int released = store.releaseStuck(sendingTimeout);
		if (released > 0) {
			log.warn("Released {} order confirmation(s) stuck in SENDING", released);
		}
		List<Long> due = store.findDue(pendingGrace, retryBackoff, maxAttempts);
		if (!due.isEmpty()) {
			log.info("Retrying {} order confirmation(s)", due.size());
		}
		due.forEach(this::process);
	}
}
