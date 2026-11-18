package com.bazaarly.service;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import java.util.List;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Rating;
import com.bazaarly.modal.User;
import com.bazaarly.request.RatingRequest;

public interface RatingServices {
	
	public Rating createRating(RatingRequest req,User user) throws ProductException;
	
	public Page<Rating> getProductsRating(Long productId, Pageable pageable);

}
