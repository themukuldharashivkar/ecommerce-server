package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Max;

import org.springframework.validation.annotation.Validated;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

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
@Tag(name = "Catalog")
@Validated
@RequestMapping("/api")
public class UserProductController {
	
	private final ProductService productService;
	private final ProductResponseCache productResponseCache;
	
	public UserProductController(ProductService productService, ProductResponseCache productResponseCache) {
		this.productService=productService;
		this.productResponseCache=productResponseCache;
	}
	
	
	// served from the catalog cache; the body is the JSON of a Page<Product>
	// every filter is optional; pageSize is capped so one request cannot load the whole catalog
	@Operation(summary = "Catalog page (cached)", description = "Returns a Spring Data Page of products: "
			+ "content, totalElements, totalPages, number, size. Filters are optional; colour and size accept "
			+ "comma-separated values; sort is price_low, price_high or empty (by id).")
	@GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> findProductByCategoryHandler(
			@RequestParam(defaultValue = "") String category,
			@RequestParam(defaultValue = "") List<String> color,
			@RequestParam(defaultValue = "") List<String> size,
			@RequestParam(required = false) @Min(value = 0, message = "minPrice cannot be negative") Integer minPrice,
			@RequestParam(required = false) @Min(value = 0, message = "maxPrice cannot be negative") Integer maxPrice,
			@RequestParam(required = false) @Min(0) @Max(100) Integer minDiscount,
			@RequestParam(defaultValue = "") String sort,
			@RequestParam(defaultValue = "") String stock,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "pageNumber must be 0 or more") Integer pageNumber,
			@RequestParam(defaultValue = "12") @Min(value = 1, message = "pageSize must be at least 1") @Max(value = 50, message = "pageSize can be at most 50") Integer pageSize)
			throws JsonProcessingException {

		String page = productResponseCache.catalogPage(new CatalogQuery(category, color, size, minPrice, maxPrice,
				minDiscount, sort, stock, pageNumber, pageSize));
		return new ResponseEntity<>(page,HttpStatus.ACCEPTED);
		
	}
	

	
	// served from the product cache; the body is the JSON of a Product
	@Operation(summary = "Product detail (cached)")
	@ApiResponse(responseCode = "202", description = "The product",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = Product.class)))
	@ApiResponse(responseCode = "404", description = "Unknown product")
	@GetMapping(value = "/products/id/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> findProductByIdHandler(@PathVariable Long productId)
			throws ProductException, JsonProcessingException {

		return new ResponseEntity<>(productResponseCache.product(productId),HttpStatus.ACCEPTED);
	}

	@GetMapping("/products/search")
	public ResponseEntity<List<Product>> searchProductHandler(
			@RequestParam @jakarta.validation.constraints.NotBlank(message = "Enter a search term") @jakarta.validation.constraints.Size(max = 100) String q,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or more") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1") @Max(value = 50, message = "size can be at most 50") int size){
		return Paged.list(productService.searchProduct(q, PageRequest.of(page, size, Sort.by("id"))), HttpStatus.OK);
		
	}
}
