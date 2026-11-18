package com.bazaarly.notification;

/** Published inside the transaction that marks an order paid; handled after that transaction commits. */
public record OrderPaidEvent(long orderId) {
}
