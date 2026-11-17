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

	record PaymentLinkRequest(double amountInPaise, String customerName, String customerContact,
			String customerEmail, String callbackUrl) {
	}

	CreatedPaymentLink createPaymentLink(PaymentLinkRequest request) throws RazorpayException;

	/** Provider status of a payment, e.g. "captured", "failed". */
	String fetchPaymentStatus(String paymentId) throws RazorpayException;
}
