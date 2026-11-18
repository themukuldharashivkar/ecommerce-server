package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

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
import com.bazaarly.service.UserService;
import com.razorpay.RazorpayException;

@RestController
@Tag(name = "Payments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequestMapping("/api")
public class PaymentController {

	private final PaymentService paymentService;
	private final UserService userService;

	public PaymentController(PaymentService paymentService, UserService userService) {
		this.paymentService = paymentService;
		this.userService = userService;
	}

	@PostMapping("/payments/{orderId}")
	public ResponseEntity<PaymentLinkResponse> createPaymentLink(@PathVariable Long orderId,
			@RequestHeader("Authorization") String jwt) throws RazorpayException, UserException, OrderException {
		String caller = userService.findUserProfileByJwt(jwt).getEmail();
		return new ResponseEntity<>(paymentService.createPaymentLink(orderId, caller), HttpStatus.ACCEPTED);
	}

	@GetMapping("/payments")
	public ResponseEntity<ApiResponse> redirect(@RequestParam(name = "payment_id") String paymentId,
			@RequestParam("order_id") Long orderId, @RequestHeader("Authorization") String jwt)
			throws RazorpayException, OrderException, UserException {
		String caller = userService.findUserProfileByJwt(jwt).getEmail();
		return new ResponseEntity<>(paymentService.confirmPayment(paymentId, orderId, caller), HttpStatus.OK);
	}

}
