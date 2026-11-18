package com.bazaarly.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.cache.CatalogQuery;
import com.bazaarly.cache.ProductResponseCache;
import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Product;
import com.bazaarly.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;

@RestController
@RequestMapping("/api")
public class UserProductController {
	
	private final ProductService productService;
	private final ProductResponseCache productResponseCache;
	
	public UserProductController(ProductService productService, ProductResponseCache productResponseCache) {
		this.productService=productService;
		this.productResponseCache=productResponseCache;
	}
	
	
	// served from the catalog cache; the body is the JSON of a Page<Product>
	@GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> findProductByCategoryHandler(@RequestParam String category,
			@RequestParam List<String>color,@RequestParam List<String> size,@RequestParam Integer minPrice,
			@RequestParam Integer maxPrice, @RequestParam Integer minDiscount, @RequestParam String sort, 
			@RequestParam String stock, @RequestParam Integer pageNumber,@RequestParam Integer pageSize)
			throws JsonProcessingException {

		String page = productResponseCache.catalogPage(new CatalogQuery(category, color, size, minPrice, maxPrice,
				minDiscount, sort, stock, pageNumber, pageSize));
		return new ResponseEntity<>(page,HttpStatus.ACCEPTED);
		
	}
	

	
	// served from the product cache; the body is the JSON of a Product
	@GetMapping(value = "/products/id/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> findProductByIdHandler(@PathVariable Long productId)
			throws ProductException, JsonProcessingException {

		return new ResponseEntity<>(productResponseCache.product(productId),HttpStatus.ACCEPTED);
	}

	@GetMapping("/products/search")
	public ResponseEntity<List<Product>> searchProductHandler(@RequestParam String q){
		
		List<Product> products=productService.searchProduct(q);
		
		return new ResponseEntity<List<Product>>(products,HttpStatus.OK);
		
	}
}
