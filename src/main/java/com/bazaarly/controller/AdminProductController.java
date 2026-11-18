package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Max;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

import java.util.List;

import jakarta.validation.Valid;
import com.bazaarly.request.UpdateProductRequest;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Product;
import com.bazaarly.request.CreateProductRequest;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.service.ProductService;

@RestController
@Tag(name = "Admin: products")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated // validates the elements of the bulk-create list
@RequestMapping("/api/admin/products")
public class AdminProductController {
	
	private ProductService productService;
	
	public AdminProductController(ProductService productService) {
		this.productService = productService;
	}
	
	@PostMapping("/")
	public ResponseEntity<Product> createProductHandler(@Valid @RequestBody CreateProductRequest req) throws ProductException{
		
		Product createdProduct = productService.createProduct(req);
		
		return new ResponseEntity<Product>(createdProduct,HttpStatus.ACCEPTED);
		
	}
	
	@DeleteMapping("/{productId}/delete")
	public ResponseEntity<ApiResponse> deleteProductHandler(@PathVariable Long productId) throws ProductException{
		
		String msg=productService.deleteProduct(productId);
		ApiResponse res=new ApiResponse(msg,true);
		
		return new ResponseEntity<ApiResponse>(res,HttpStatus.ACCEPTED);
		
	}
	
	@GetMapping("/all")
	public ResponseEntity<List<Product>> findAllProduct(@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or more") int page,
			@RequestParam(defaultValue = "50") @Min(value = 1, message = "size must be at least 1") @Max(value = 100, message = "size can be at most 100") int size){
		return Paged.list(productService.getAllProducts(PageRequest.of(page, size, Sort.by("id"))), HttpStatus.OK);
	}
	
	@PutMapping("/{productId}/update")
	public ResponseEntity<Product> updateProductHandler(@Valid @RequestBody UpdateProductRequest req,@PathVariable Long productId) throws ProductException{
		
		Product updatedProduct=productService.updateProduct(productId, req);
		
		return new ResponseEntity<Product>(updatedProduct,HttpStatus.OK);
	}
	
	@PostMapping("/creates")
	public ResponseEntity<ApiResponse> createMultipleProduct(
			@RequestBody @Size(min = 1, max = 100, message = "Send between 1 and 100 products") List<@Valid CreateProductRequest> reqs)
			throws ProductException{
		
		for(CreateProductRequest product:reqs) {
			productService.createProduct(product);
		}
		
		ApiResponse res=new ApiResponse("products created successfully",true);
		return new ResponseEntity<ApiResponse>(res,HttpStatus.ACCEPTED);
	}

}
