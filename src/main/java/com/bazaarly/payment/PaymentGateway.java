package com.bazaarly.payment;

import com.razorpay.RazorpayException;

/**
 * The only place the application talks to the payment provider.
 * Tests replace it with a mock, so they never call Razorpay.
 */
public interface PaymentGateway {

	/**
	 * A hosted payment link the shopper is redirected to. providerOrderId is usually null at creation:
	 * Razorpay creates the underlying order only when the link is paid.
	 */
	record CreatedPaymentLink(String id, String shortUrl, String providerOrderId) {
	}

	/**
	 * A payment link as Razorpay reports it now: status ("created", "paid", "expired", "cancelled", ...),
	 * the order behind it once it has been paid, and the ids of the payments made through it.
	 */
	record PaymentLinkState(String id, String shortUrl, String status, String providerOrderId,
			java.util.List<String> paymentIds) {

		/** The shopper can still pay this link. */
		public boolean isPayable() {
			return "created".equals(status) || "partially_paid".equals(status);
		}
	}

	PaymentLinkState fetchPaymentLink(String linkId) throws RazorpayException;

	/** expireBy: when the link stops accepting payments (Unix seconds; Razorpay needs at least 15 minutes ahead). */
	record PaymentLinkRequest(long amountInPaise, String customerName, String customerContact,
			String customerEmail, String callbackUrl, long expireBy) {
	}

	CreatedPaymentLink createPaymentLink(PaymentLinkRequest request) throws RazorpayException;

	/**
	 * A payment as the provider reports it: status (e.g. "captured", "failed"), the provider order it
	 * belongs to (for payment links, the order id stored when the link was created) and the amount.
	 */
	record ProviderPayment(String status, String providerOrderId, long amountInPaise) {
	}

	ProviderPayment fetchPayment(String paymentId) throws RazorpayException;

	record Refund(String id, String status) {
	}

	/**
	 * Refunds the payment in full. Idempotent: if the payment is already fully refunded (e.g. a retry
	 * after a timeout), no new refund is created and the existing one is returned.
	 */
	Refund refundInFull(String paymentId, long amountInPaise, String reason) throws RazorpayException;
}
