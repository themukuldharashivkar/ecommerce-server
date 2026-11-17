package com.bazaarly.service;

import java.util.List;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Rating;
import com.bazaarly.modal.User;
import com.bazaarly.request.RatingRequest;

public interface RatingServices {
	
	public Rating createRating(RatingRequest req,User user) throws ProductException;
	
	public List<Rating> getProductsRating(Long productId);

}
