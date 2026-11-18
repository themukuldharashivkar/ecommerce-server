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
import com.bazaarly.modal.Review;
import com.bazaarly.modal.User;
import com.bazaarly.repository.ProductRepository;
import com.bazaarly.repository.ReviewRepository;
import com.bazaarly.request.ReviewRequest;

@Service
public class ReviewServiceImplementation implements ReviewService {
	
	private ReviewRepository reviewRepository;
	private ProductService productService;
	private ProductRepository productRepository;
	
	public ReviewServiceImplementation(ReviewRepository reviewRepository,ProductService productService,ProductRepository productRepository) {
		this.reviewRepository=reviewRepository;
		this.productService=productService;
		this.productRepository=productRepository;
	}

	@Override
	// product JSON (detail and catalog pages) embeds its reviews and ratings
	@Caching(evict = { @CacheEvict(cacheNames = CacheNames.CATALOG, allEntries = true),
			@CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#req.productId") })
	public Review createReview(ReviewRequest req,User user) throws ProductException {
		// TODO Auto-generated method stub
		Product product=productService.findProductById(req.getProductId());
		Review review=new Review();
		review.setUser(user);
		review.setProduct(product);
		review.setReview(req.getReview());
		review.setCreatedAt(LocalDateTime.now());
		
//		product.getReviews().add(review);
		productRepository.save(product);
		return reviewRepository.save(review);
	}

	@Override
	public Page<Review> getAllReview(Long productId, Pageable pageable) {
		
		return reviewRepository.getAllProductsReview(productId, pageable);
	}

}
