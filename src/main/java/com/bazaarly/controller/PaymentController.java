package com.bazaarly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.OrderException;
import com.bazaarly.exception.UserException;
import com.bazaarly.payment.PaymentService;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.response.PaymentLinkResponse;
import com.razorpay.RazorpayException;

@RestController
@RequestMapping("/api")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping("/payments/{orderId}")
	public ResponseEntity<PaymentLinkResponse> createPaymentLink(@PathVariable Long orderId,
			@RequestHeader("Authorization") String jwt) throws RazorpayException, UserException, OrderException {
		return new ResponseEntity<>(paymentService.createPaymentLink(orderId), HttpStatus.ACCEPTED);
	}

	@GetMapping("/payments")
	public ResponseEntity<ApiResponse> redirect(@RequestParam(name = "payment_id") String paymentId,
			@RequestParam("order_id") Long orderId) throws RazorpayException, OrderException {
		return new ResponseEntity<>(paymentService.confirmPayment(paymentId, orderId), HttpStatus.OK);
	}

}
