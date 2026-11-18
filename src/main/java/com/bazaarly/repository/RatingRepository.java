package com.bazaarly.repository;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bazaarly.modal.Rating;

public interface RatingRepository extends JpaRepository<Rating, Long> {
	
	@Query("Select r From Rating r where r.product.id=:productId")
	public Page<Rating> getAllProductsRating(@Param("productId") Long productId, Pageable pageable);

}
