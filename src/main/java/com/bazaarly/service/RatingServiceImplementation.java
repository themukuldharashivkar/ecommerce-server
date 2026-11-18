package com.bazaarly.service;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import com.bazaarly.cache.CacheNames;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.Rating;
import com.bazaarly.modal.User;
import com.bazaarly.repository.RatingRepository;
import com.bazaarly.request.RatingRequest;

@Service
public class RatingServiceImplementation implements RatingServices{
	
	private RatingRepository ratingRepository;
	private ProductService productService;
	
	public RatingServiceImplementation(RatingRepository ratingRepository,ProductService productService) {
		this.ratingRepository=ratingRepository;
		this.productService=productService;
	}

	@Override
	// product JSON (detail and catalog pages) embeds its reviews and ratings
	@Caching(evict = { @CacheEvict(cacheNames = CacheNames.CATALOG, allEntries = true),
			@CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#req.productId") })
	public Rating createRating(RatingRequest req,User user) throws ProductException {
		
		Product product=productService.findProductById(req.getProductId());
		
		Rating rating=new Rating();
		rating.setProduct(product);
		rating.setUser(user);
		rating.setRating(req.getRating());
		rating.setCreatedAt(LocalDateTime.now());
		
		return ratingRepository.save(rating);
	}

	@Override
	public Page<Rating> getProductsRating(Long productId, Pageable pageable) {
		// TODO Auto-generated method stub
		return ratingRepository.getAllProductsRating(productId, pageable);
	}
	
	

}
