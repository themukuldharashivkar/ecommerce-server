package com.bazaarly.service;

import com.bazaarly.exception.ProductNotFoundException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import com.bazaarly.cache.CacheNames;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Category;
import com.bazaarly.modal.Product;
import com.bazaarly.repository.CategoryRepository;
import com.bazaarly.repository.ProductRepository;
import com.bazaarly.repository.ProductSpecifications;
import com.bazaarly.request.CreateProductRequest;
import com.bazaarly.request.UpdateProductRequest;
import com.bazaarly.user.domain.ProductSubCategory;

@Service
public class ProductServiceImplementation implements ProductService {
	
	private ProductRepository productRepository;
	private UserService userService;
	private CategoryRepository categoryRepository;
	
	public ProductServiceImplementation(ProductRepository productRepository,UserService userService,CategoryRepository categoryRepository) {
		this.productRepository=productRepository;
		this.userService=userService;
		this.categoryRepository=categoryRepository;
	}
	

	@Override
	// a new product can appear on any catalog page
	@CacheEvict(cacheNames = CacheNames.CATALOG, allEntries = true)
	public Product createProduct(CreateProductRequest req) {
		
		Category topLevel=categoryRepository.findByName(req.getTopLavelCategory());
		
		if(topLevel==null) {
			
			Category topLavelCategory=new Category();
			topLavelCategory.setName(req.getTopLavelCategory());
			topLavelCategory.setLevel(1);
			
			topLevel= categoryRepository.save(topLavelCategory);
		}
		
		Category secondLevel=categoryRepository.
				findByNameAndParant(req.getSecondLavelCategory(),topLevel.getName());
		if(secondLevel==null) {
			
			Category secondLavelCategory=new Category();
			secondLavelCategory.setName(req.getSecondLavelCategory());
			secondLavelCategory.setParentCategory(topLevel);
			secondLavelCategory.setLevel(2);
			
			secondLevel= categoryRepository.save(secondLavelCategory);
		}

		Category thirdLevel=categoryRepository.findByNameAndParant(req.getThirdLavelCategory(),secondLevel.getName());
		if(thirdLevel==null) {
			
			Category thirdLavelCategory=new Category();
			thirdLavelCategory.setName(req.getThirdLavelCategory());
			thirdLavelCategory.setParentCategory(secondLevel);
			thirdLavelCategory.setLevel(3);
			
			thirdLevel=categoryRepository.save(thirdLavelCategory);
		}
		
		
		Product product=new Product();
		product.setTitle(req.getTitle());
		product.setColor(req.getColor());
		product.setDescription(req.getDescription());
		product.setDiscountedPrice(req.getDiscountedPrice());
		product.setDiscountPersent(req.getDiscountPersent());
		product.setImageUrl(req.getImageUrl());
		product.setBrand(req.getBrand());
		product.setPrice(req.getPrice());
		product.setSizes(req.getSize());
		product.setQuantity(req.getQuantity());
		product.setCategory(thirdLevel);
		product.setCreatedAt(LocalDateTime.now());
		
		Product savedProduct= productRepository.save(product);
		
		
		return savedProduct;
	}

	@Override
	@Caching(evict = { @CacheEvict(cacheNames = CacheNames.CATALOG, allEntries = true),
			@CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId") })
	public String deleteProduct(Long productId) throws ProductException {
		
		Product product=findProductById(productId);
		
		product.getSizes().clear();
//		productRepository.save(product);
//		product.getCategory().
		productRepository.delete(product);
		
		return "Product deleted Successfully";
	}

	@Override
	@Caching(evict = { @CacheEvict(cacheNames = CacheNames.CATALOG, allEntries = true),
			@CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId") })
	public Product updateProduct(Long productId,UpdateProductRequest req) throws ProductException {
		Product product=findProductById(productId);
		// null means "leave unchanged"; 0 is a valid quantity (sold out)
		if(req.quantity()!=null) {
			product.setQuantity(req.quantity());
		}
		if(req.description()!=null) {
			product.setDescription(req.description());
		}
		return productRepository.save(product);
	}

	@Override
	public Page<Product> getAllProducts(Pageable pageable) {
		return productRepository.findAll(pageable);
	}

	@Override
	public Product findProductById(Long id) throws ProductException {
		Optional<Product> opt=productRepository.findById(id);
		
		if(opt.isPresent()) {
			return opt.get();
		}
		throw new ProductNotFoundException("product not found with id "+id);
	}

	@Override
	public List<Product> findProductByCategory(String category) {
		
		
		List<Product> products = productRepository.findByCategory(category);
		
		return products;
	}

	@Override
	public Page<Product> searchProduct(String query, Pageable pageable) {
		// the query lower-cases the columns, so lower-case the term too (PostgreSQL LIKE is case-sensitive)
		String term = query == null ? "" : query.toLowerCase();
		List<Long> categoryIds = categoryRepository.findIdsByNameContaining(term);
		if (categoryIds.isEmpty()) {
			categoryIds = List.of(-1L); // keeps the IN clause valid; matches nothing
		}
		return productRepository.searchProduct(term, categoryIds, pageable);
	}



	
	
	@Override
	public Page<Product> getAllProduct(String category, List<String>colors, 
			List<String> sizes, Integer minPrice, Integer maxPrice, 
			Integer minDiscount,String sort, String stock, Integer pageNumber, Integer pageSize ) {

		// filtering, sorting and paging all happen in the database
		return productRepository.findAll(
				ProductSpecifications.catalog(category, colors, sizes, minPrice, maxPrice, minDiscount, stock),
				PageRequest.of(pageNumber, pageSize, ProductSpecifications.catalogSort(sort)));
	}

}
