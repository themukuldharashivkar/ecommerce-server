package com.bazaarly.service;

import java.util.List;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Review;
import com.bazaarly.modal.User;
import com.bazaarly.request.ReviewRequest;

public interface ReviewService {

	public Review createReview(ReviewRequest req,User user) throws ProductException;
	
	public List<Review> getAllReview(Long productId);
	
	
}
