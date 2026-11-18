package com.bazaarly.payment;

import com.razorpay.RazorpayException;

/**
 * The only place the application talks to the payment provider.
 * Tests replace it with a mock, so they never call Razorpay.
 */
public interface PaymentGateway {

	/** A hosted payment link the shopper is redirected to. */
	record CreatedPaymentLink(String id, String shortUrl, String providerOrderId) {
	}

	record PaymentLinkRequest(long amountInPaise, String customerName, String customerContact,
			String customerEmail, String callbackUrl) {
	}

	CreatedPaymentLink createPaymentLink(PaymentLinkRequest request) throws RazorpayException;

	/**
	 * A payment as the provider reports it: status (e.g. "captured", "failed"), the provider order it
	 * belongs to (for payment links, the order id stored when the link was created) and the amount.
	 */
	record ProviderPayment(String status, String providerOrderId, long amountInPaise) {
	}

	ProviderPayment fetchPayment(String paymentId) throws RazorpayException;
}
