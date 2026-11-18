package com.bazaarly.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bazaarly.modal.Product;
import com.bazaarly.user.domain.ProductSubCategory;


public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

	@Query("SELECT p From Product p Where LOWER(p.category.name)=:category")
	public List<Product> findByCategory(@Param("category") String category);
	
	// Category matches are passed in as ids (resolved first from the small categories table) so every
	// condition is on product and PostgreSQL can combine the trigram and category indexes (see V2).
	@Query(value = "SELECT p From Product p where LOWER(p.title) LIKE %:query% OR LOWER(p.description) LIKE %:query% "
			+ "OR LOWER(p.brand) LIKE %:query% OR p.category.id IN :categoryIds",
			countQuery = "SELECT count(p) From Product p where LOWER(p.title) LIKE %:query% OR LOWER(p.description) LIKE %:query% "
			+ "OR LOWER(p.brand) LIKE %:query% OR p.category.id IN :categoryIds")
	public Page<Product> searchProduct(@Param("query") String query, @Param("categoryIds") List<Long> categoryIds, Pageable pageable);
	


	

}
