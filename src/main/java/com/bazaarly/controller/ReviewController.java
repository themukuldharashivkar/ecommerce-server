package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Max;

import org.springframework.validation.annotation.Validated;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.ProductException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.Review;
import com.bazaarly.modal.User;
import com.bazaarly.request.ReviewRequest;
import com.bazaarly.service.ReviewService;
import com.bazaarly.service.UserService;

@RestController
@Tag(name = "Reviews")
@Validated
@RequestMapping("/api/reviews")
public class ReviewController {
	
	private ReviewService reviewService;
	private UserService userService;
	
	public ReviewController(ReviewService reviewService,UserService userService) {
		this.reviewService=reviewService;
		this.userService=userService;
		// TODO Auto-generated constructor stub
	}
	@SecurityRequirement(name = OpenApiConfig.BEARER)
	@PostMapping("/create")
	public ResponseEntity<Review> createReviewHandler(@Valid @RequestBody ReviewRequest req,@RequestHeader("Authorization") String jwt) throws UserException, ProductException{
		User user=userService.findUserProfileByJwt(jwt);
		Review review=reviewService.createReview(req, user);
		return new ResponseEntity<Review>(review,HttpStatus.ACCEPTED);
	}
	
	@GetMapping("/product/{productId}")
	public ResponseEntity<List<Review>> getProductsReviewHandler(@PathVariable Long productId,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or more") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1") @Max(value = 100, message = "size can be at most 100") int size){
		return Paged.list(reviewService.getAllReview(productId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("id")))), HttpStatus.OK);
	}

}
