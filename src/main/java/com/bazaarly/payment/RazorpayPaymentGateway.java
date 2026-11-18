package com.bazaarly.payment;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;

/** Razorpay Payment Links (test mode keys only). */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

	private final String keyId;
	private final String keySecret;

	public RazorpayPaymentGateway(@Value("${razorpay.key-id}") String keyId,
			@Value("${razorpay.key-secret}") String keySecret) {
		this.keyId = keyId;
		this.keySecret = keySecret;
	}

	private RazorpayClient client() throws RazorpayException {
		return new RazorpayClient(keyId, keySecret);
	}

	@Override
	public CreatedPaymentLink createPaymentLink(PaymentLinkRequest request) throws RazorpayException {
		RazorpayClient razorpay = client();

		JSONObject paymentLinkRequest = new JSONObject();
		paymentLinkRequest.put("amount", request.amountInPaise());
		paymentLinkRequest.put("currency", "INR");

		JSONObject customer = new JSONObject();
		customer.put("name", request.customerName());
		customer.put("contact", request.customerContact());
		customer.put("email", request.customerEmail());
		paymentLinkRequest.put("customer", customer);

		JSONObject notify = new JSONObject();
		notify.put("sms", true);
		notify.put("email", true);
		paymentLinkRequest.put("notify", notify);
		paymentLinkRequest.put("reminder_enable", true);

		paymentLinkRequest.put("callback_url", request.callbackUrl());
		// the order's stock reservation ends then; an expired link cannot be paid
		paymentLinkRequest.put("expire_by", request.expireBy());
		paymentLinkRequest.put("callback_method", "get");

		PaymentLink payment = razorpay.paymentLink.create(paymentLinkRequest);
		String paymentLinkId = payment.get("id");
		String paymentLinkUrl = payment.get("short_url");

		// the Razorpay order id is only available on the fetched link
		PaymentLink fetchedPayment = razorpay.paymentLink.fetch(paymentLinkId);
		String providerOrderId = fetchedPayment.get("order_id");

		log.info("Created Razorpay payment link {}", paymentLinkId);
		return new CreatedPaymentLink(paymentLinkId, paymentLinkUrl, providerOrderId);
	}

	@Override
	public ProviderPayment fetchPayment(String paymentId) throws RazorpayException {
		JSONObject payment = client().payments.fetch(paymentId).toJson();
		// optString/optLong: order_id can be JSON null for payments not made through an order
		return new ProviderPayment(payment.optString("status", null), payment.optString("order_id", null),
				payment.optLong("amount", -1));
	}
}
