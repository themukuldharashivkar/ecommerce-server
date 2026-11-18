package com.bazaarly.service;

import org.springframework.data.domain.Pageable;

import java.util.List;

import org.springframework.data.domain.Page;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Product;
import com.bazaarly.request.CreateProductRequest;
import com.bazaarly.request.UpdateProductRequest;
import com.bazaarly.user.domain.ProductSubCategory;

public interface ProductService {
	
	// only for admin
	public Product createProduct(CreateProductRequest req) throws ProductException;
	
	public String deleteProduct(Long productId) throws ProductException;
	
	public Product updateProduct(Long productId,UpdateProductRequest req)throws ProductException;
	
	public Page<Product> getAllProducts(Pageable pageable);
	
	// for user and admin both
	public Product findProductById(Long id) throws ProductException;
	
	public List<Product> findProductByCategory(String category);
	
	public Page<Product> searchProduct(String query, Pageable pageable);
	
//	public List<Product> getAllProduct(List<String>colors,List<String>sizes,int minPrice, int maxPrice,int minDiscount, String category, String sort,int pageNumber, int pageSize);
	public Page<Product> getAllProduct(String category, List<String>colors, List<String> sizes, Integer minPrice, Integer maxPrice, Integer minDiscount,String sort, String stock, Integer pageNumber, Integer pageSize);
	
	
	
	

}
