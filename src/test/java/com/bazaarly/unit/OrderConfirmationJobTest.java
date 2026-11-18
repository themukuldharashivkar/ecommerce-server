package com.bazaarly.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bazaarly.notification.OrderConfirmationJob;
import com.bazaarly.notification.OrderConfirmationMessage;
import com.bazaarly.notification.OrderConfirmationMessageBuilder;
import com.bazaarly.notification.OrderConfirmationSender;
import com.bazaarly.notification.OrderNotificationStore;
import com.bazaarly.notification.OrderPaidEvent;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationJobTest {

	@Mock OrderNotificationStore store;
	@Mock OrderConfirmationMessageBuilder builder;
	@Mock OrderConfirmationSender sender;

	OrderConfirmationJob job;
	OrderConfirmationMessage message = new OrderConfirmationMessage(7L, "a@b.c", "Bazaarly order #7 confirmed", "body");

	@BeforeEach
	void setUp() {
		job = new OrderConfirmationJob(store, builder, sender, 5, Duration.ofMinutes(1), Duration.ofMinutes(2),
				Duration.ofMinutes(10));
	}

	@Test
	void claimedNotificationIsSentAndMarkedSent() throws Exception {
		when(store.claim(7L, 5)).thenReturn(true);
		when(builder.build(7L)).thenReturn(message);

		job.onOrderPaid(new OrderPaidEvent(7L));

		verify(sender).send(message);
		verify(store).markSent(7L);
		verify(store, never()).markFailed(anyLong(), any());
	}

	@Test
	void notificationThatCannotBeClaimedIsNeverSent() throws Exception {
		when(store.claim(7L, 5)).thenReturn(false); // already sent, in progress elsewhere, or out of attempts

		job.process(7L);

		verify(sender, never()).send(any());
		verify(store, never()).markSent(anyLong());
	}

	@Test
	void sendFailureIsRecordedForRetry() throws Exception {
		when(store.claim(7L, 5)).thenReturn(true);
		when(builder.build(7L)).thenReturn(message);
		doThrow(new IllegalStateException("SMTP down")).when(sender).send(message);

		job.process(7L);

		verify(store).markFailed(eq(7L), contains("SMTP down"));
		verify(store, never()).markSent(anyLong());
	}

	@Test
	void orderThatNoLongerExistsIsRecordedAsFailed() throws Exception {
		when(store.claim(7L, 5)).thenReturn(true);
		when(builder.build(7L)).thenThrow(new IllegalStateException("order 7 no longer exists"));

		job.process(7L);

		verify(store).markFailed(eq(7L), contains("no longer exists"));
		verify(sender, never()).send(any());
	}

	@Test
	void sweeperReleasesStuckRowsThenRetriesEveryDueOrder() throws Exception {
		when(store.findDue(Duration.ofMinutes(2), Duration.ofMinutes(1), 5)).thenReturn(List.of(7L, 8L));
		when(store.claim(anyLong(), anyInt())).thenReturn(true);
		when(builder.build(anyLong())).thenReturn(message);

		job.retryDue();

		verify(store).releaseStuck(Duration.ofMinutes(10));
		verify(store).claim(7L, 5);
		verify(store).claim(8L, 5);
		verify(store).markSent(7L);
		verify(store).markSent(8L);
	}
}
