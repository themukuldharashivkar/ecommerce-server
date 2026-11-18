package com.bazaarly.notification;

import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the order_notification outbox (V3 migration). Plain SQL, because the guarantees
 * live in the statements: INSERT ... ON CONFLICT DO NOTHING for "at most one row per order" and a
 * conditional UPDATE as an atomic claim, so two workers can never send the same notification.
 */
@Repository
public class OrderNotificationStore {

	static final String TYPE = "ORDER_CONFIRMATION";
	private static final int MAX_ERROR_LENGTH = 500;

	private final JdbcTemplate jdbc;

	public OrderNotificationStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Records that the order needs a confirmation. Returns false if one was already recorded. */
	public boolean createIfAbsent(long orderId) {
		return jdbc.update("""
				INSERT INTO order_notification (order_id, type, status, attempts, created_at, updated_at)
				VALUES (?, ?, 'PENDING', 0, now(), now())
				ON CONFLICT (order_id, type) DO NOTHING""", orderId, TYPE) == 1;
	}

	/**
	 * Atomically takes ownership of an unsent notification. Only one caller can win: the row must
	 * still be PENDING or FAILED, and the attempt limit must not be reached.
	 */
	public boolean claim(long orderId, int maxAttempts) {
		return jdbc.update("""
				UPDATE order_notification
				SET status = 'SENDING', attempts = attempts + 1, updated_at = now()
				WHERE order_id = ? AND type = ? AND status IN ('PENDING', 'FAILED') AND attempts < ?""",
				orderId, TYPE, maxAttempts) == 1;
	}

	public void markSent(long orderId) {
		jdbc.update("""
				UPDATE order_notification SET status = 'SENT', sent_at = now(), updated_at = now(), last_error = NULL
				WHERE order_id = ? AND type = ?""", orderId, TYPE);
	}

	public void markFailed(long orderId, String error) {
		String message = error == null ? "unknown error" : error;
		if (message.length() > MAX_ERROR_LENGTH) {
			message = message.substring(0, MAX_ERROR_LENGTH);
		}
		jdbc.update("""
				UPDATE order_notification SET status = 'FAILED', last_error = ?, updated_at = now()
				WHERE order_id = ? AND type = ?""", message, orderId, TYPE);
	}

	/**
	 * A worker that died mid-send leaves its row in SENDING. After the timeout, hand it back for a retry.
	 * (If the email actually went out before the crash, the retry sends it again: at-least-once in that case.)
	 */
	public int releaseStuck(Duration sendingTimeout) {
		return jdbc.update("""
				UPDATE order_notification SET status = 'FAILED', last_error = 'timed out while sending', updated_at = now()
				WHERE status = 'SENDING' AND updated_at < now() - make_interval(secs => ?)""",
				sendingTimeout.toSeconds());
	}

	/**
	 * Orders whose notification should be (re)tried now:
	 * PENDING rows the async job never picked up (e.g. the app restarted or the queue was full), and
	 * FAILED rows whose backoff (attempts x retryBackoff) has passed and that are under the attempt limit.
	 */
	public List<Long> findDue(Duration pendingGrace, Duration retryBackoff, int maxAttempts) {
		return jdbc.queryForList("""
				SELECT order_id FROM order_notification
				WHERE type = ?
				  AND ((status = 'PENDING' AND updated_at < now() - make_interval(secs => ?))
				    OR (status = 'FAILED' AND attempts < ?
				        AND updated_at < now() - make_interval(secs => attempts * ?)))
				ORDER BY updated_at
				LIMIT 100""", Long.class,
				TYPE, pendingGrace.toSeconds(), maxAttempts, retryBackoff.toSeconds());
	}

	/** For tests and diagnostics. */
	public NotificationState find(long orderId) {
		List<NotificationState> rows = jdbc.query(
				"SELECT status, attempts, last_error, sent_at IS NOT NULL AS sent FROM order_notification WHERE order_id = ? AND type = ?",
				(rs, i) -> new NotificationState(rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"), rs.getBoolean("sent")),
				orderId, TYPE);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public record NotificationState(String status, int attempts, String lastError, boolean sent) {
	}
}
