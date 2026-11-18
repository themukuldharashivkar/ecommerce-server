package com.bazaarly.notification;

public record OrderConfirmationMessage(long orderId, String to, String subject, String body) {
}
