package com.bazaarly.config;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bazaarly.repository.ProductRepository;
import com.bazaarly.request.CreateProductRequest;
import com.bazaarly.service.ProductService;

/**
 * Loads demo products from classpath:seed/products.json on startup,
 * but only when the product table is empty (so it never duplicates data).
 * Disable with app.seed.enabled=false (tests, schema generation).
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

	private final ProductRepository productRepository;
	private final ProductService productService;
	private final ObjectMapper objectMapper;

	public DataSeeder(ProductRepository productRepository, ProductService productService, ObjectMapper objectMapper) {
		this.productRepository = productRepository;
		this.productService = productService;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(String... args) throws Exception {
		if (productRepository.count() > 0) {
			return;
		}
		try (InputStream in = new ClassPathResource("seed/products.json").getInputStream()) {
			CreateProductRequest[] products = objectMapper.readValue(in, CreateProductRequest[].class);
			for (CreateProductRequest product : products) {
				productService.createProduct(product);
			}
			log.info("Seeded {} demo products", products.length);
		}
	}
}
