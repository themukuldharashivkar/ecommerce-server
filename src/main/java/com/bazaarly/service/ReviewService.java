package com.bazaarly.service;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import java.util.List;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Review;
import com.bazaarly.modal.User;
import com.bazaarly.request.ReviewRequest;

public interface ReviewService {

	public Review createReview(ReviewRequest req,User user) throws ProductException;
	
	public Page<Review> getAllReview(Long productId, Pageable pageable);
	
	
}
