package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.bazaarly.exception.ProductNotFoundException;
import com.bazaarly.modal.Product;
import com.bazaarly.repository.CategoryRepository;
import com.bazaarly.repository.ProductRepository;
import com.bazaarly.repository.ProductSpecifications;
import com.bazaarly.request.UpdateProductRequest;
import com.bazaarly.service.ProductServiceImplementation;
import com.bazaarly.service.UserService;

/**
 * Catalog filtering itself runs in the database (ProductSpecifications) and is tested against PostgreSQL
 * in ProductQueriesIntegrationTest. These tests cover what the service decides.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	@Mock ProductRepository productRepository;
	@Mock UserService userService;
	@Mock CategoryRepository categoryRepository;

	ProductServiceImplementation service;

	@BeforeEach
	void setUp() {
		service = new ProductServiceImplementation(productRepository, userService, categoryRepository);
	}

	@SuppressWarnings("unchecked")
	private Pageable catalogPageRequestFor(String sort, int page, int size) {
		when(productRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
		service.getAllProduct("men_jeans", List.of(), List.of(), 0, 1000, 0, sort, "", page, size);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(productRepository).findAll(any(Specification.class), pageable.capture());
		return pageable.getValue();
	}

	@Test
	void catalogIsPagedByTheDatabase() {
		Pageable pageable = catalogPageRequestFor("price_low", 2, 12);
		assertThat(pageable.getPageNumber()).isEqualTo(2);
		assertThat(pageable.getPageSize()).isEqualTo(12);
	}

	@Test
	void catalogSortsByShownPriceThenId() {
		assertThat(catalogPageRequestFor("price_low", 0, 12).getSort())
				.isEqualTo(Sort.by(Sort.Order.asc("discountedPrice"), Sort.Order.asc("id")));
	}

	@Test
	void catalogSortOptions() {
		assertThat(ProductSpecifications.catalogSort("price_high"))
				.isEqualTo(Sort.by(Sort.Order.desc("discountedPrice"), Sort.Order.asc("id")));
		assertThat(ProductSpecifications.catalogSort("")).isEqualTo(Sort.by(Sort.Order.asc("id")));
		assertThat(ProductSpecifications.catalogSort(null)).isEqualTo(Sort.by(Sort.Order.asc("id")));
	}

	@Test
	void searchLowerCasesTheTermAndPassesMatchingCategoryIds() {
		Pageable page = PageRequest.of(0, 20);
		Product match = new Product();
		when(categoryRepository.findIdsByNameContaining("jeans")).thenReturn(List.of(7L, 12L));
		when(productRepository.searchProduct("jeans", List.of(7L, 12L), page)).thenReturn(new PageImpl<>(List.of(match)));

		assertThat(service.searchProduct("JeAnS", page).getContent()).containsExactly(match);
	}

	@Test
	void searchWithNoMatchingCategoryStillProducesAValidQuery() {
		Pageable page = PageRequest.of(0, 20);
		when(categoryRepository.findIdsByNameContaining("silk")).thenReturn(List.of());
		when(productRepository.searchProduct(eq("silk"), eq(List.of(-1L)), eq(page))).thenReturn(Page.empty());

		assertThat(service.searchProduct("silk", page)).isEmpty();
	}

	@Test
	void findProductByIdOfAnUnknownProductIsNotFound() {
		when(productRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findProductById(99L))
				.isInstanceOf(ProductNotFoundException.class)
				.hasMessage("product not found with id 99");
	}

	@Test
	void updateProductOnlyChangesProvidedFields() throws Exception {
		Product existing = new Product();
		existing.setQuantity(4);
		existing.setDescription("old");
		when(productRepository.findById(5L)).thenReturn(Optional.of(existing));
		when(productRepository.save(existing)).thenReturn(existing);

		Product updated = service.updateProduct(5L, new UpdateProductRequest(9, null));

		assertThat(updated.getQuantity()).isEqualTo(9);
		assertThat(updated.getDescription()).isEqualTo("old");
	}

	@Test
	void updateProductCanMarkAProductSoldOut() throws Exception {
		Product existing = new Product();
		existing.setQuantity(4);
		when(productRepository.findById(5L)).thenReturn(Optional.of(existing));
		when(productRepository.save(existing)).thenReturn(existing);

		// previously a quantity of 0 was treated as "not provided"
		assertThat(service.updateProduct(5L, new UpdateProductRequest(0, null)).getQuantity()).isZero();
	}
}
