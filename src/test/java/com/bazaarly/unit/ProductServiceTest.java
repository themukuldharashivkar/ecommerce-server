package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.Size;
import com.bazaarly.repository.CategoryRepository;
import com.bazaarly.repository.ProductRepository;
import com.bazaarly.service.ProductServiceImplementation;
import com.bazaarly.service.UserService;

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

	private static Size size(String name, int quantity) {
		Size size = new Size();
		size.setName(name);
		size.setQuantity(quantity);
		return size;
	}

	private static Product product(long id, String color, int stock, Size... sizes) {
		Product p = new Product();
		p.setId(id);
		p.setTitle("p" + id);
		p.setColor(color);
		p.setQuantity(stock);
		p.setSizes(new java.util.HashSet<>(Set.of(sizes)));
		return p;
	}

	private final List<Product> catalog = List.of(
			product(1, "Red", 5, size("M", 2)),
			product(2, "Blue", 0, size("M", 0), size("L", 1)),
			product(3, "red", 3, size("S", 4)),
			product(4, "Green", 1, size("L", 3)));

	private Page<Product> filter(List<String> colors, List<String> sizes, String stock, int page, int pageSize) {
		when(productRepository.filterProducts(anyString(), any(), any(), any(), anyString())).thenReturn(catalog);
		return service.getAllProduct("cat", colors, sizes, 0, 100000, 0, "price_low", stock, page, pageSize);
	}

	private static List<Long> ids(Page<Product> page) {
		return page.getContent().stream().map(Product::getId).toList();
	}

	@Test
	void noExtraFiltersReturnsEverythingTheQueryFound() {
		assertThat(ids(filter(List.of(), List.of(), null, 0, 10))).containsExactly(1L, 2L, 3L, 4L);
	}

	@Test
	void colourFilterIgnoresCase() {
		assertThat(ids(filter(List.of("RED"), List.of(), null, 0, 10))).containsExactly(1L, 3L);
		assertThat(ids(filter(List.of("red", "green"), List.of(), null, 0, 10))).containsExactly(1L, 3L, 4L);
	}

	@Test
	void sizeFilterOnlyMatchesSizesInStock() {
		// product 2 lists M but has none left
		assertThat(ids(filter(List.of(), List.of("m"), null, 0, 10))).containsExactly(1L);
		assertThat(ids(filter(List.of(), List.of("L"), null, 0, 10))).containsExactly(2L, 4L);
	}

	@Test
	void stockFilter() {
		assertThat(ids(filter(List.of(), List.of(), "in_stock", 0, 10))).containsExactly(1L, 3L, 4L);
		assertThat(ids(filter(List.of(), List.of(), "out_of_stock", 0, 10))).containsExactly(2L);
	}

	@Test
	void pagesTheFilteredList() {
		Page<Product> first = filter(List.of(), List.of(), null, 0, 3);
		assertThat(ids(first)).containsExactly(1L, 2L, 3L);
		assertThat(first.getTotalElements()).isEqualTo(4);
		assertThat(first.getTotalPages()).isEqualTo(2);

		assertThat(ids(filter(List.of(), List.of(), null, 1, 3))).containsExactly(4L);
	}

	@Test
	void searchLowerCasesTheTermAndPassesMatchingCategoryIds() {
		when(categoryRepository.findIdsByNameContaining("jeans")).thenReturn(List.of(7L, 12L));
		when(productRepository.searchProduct("jeans", List.of(7L, 12L))).thenReturn(List.of(catalog.get(0)));

		assertThat(service.searchProduct("JeAnS")).hasSize(1);
	}

	@Test
	void searchWithNoMatchingCategoryStillProducesAValidQuery() {
		when(categoryRepository.findIdsByNameContaining("silk")).thenReturn(List.of());
		when(productRepository.searchProduct("silk", List.of(-1L))).thenReturn(List.of());

		assertThat(service.searchProduct("silk")).isEmpty();
		verify(productRepository).searchProduct("silk", List.of(-1L));
	}

	@Test
	void findProductByIdOfAnUnknownProductFails() {
		when(productRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findProductById(99L))
				.isInstanceOf(ProductException.class)
				.hasMessage("product not found with id 99");
	}

	@Test
	void updateProductOnlyChangesProvidedFields() throws Exception {
		Product existing = product(5, "Red", 4, size("M", 1));
		existing.setDescription("old");
		when(productRepository.findById(5L)).thenReturn(Optional.of(existing));
		when(productRepository.save(existing)).thenReturn(existing);
		Product change = new Product();
		change.setQuantity(9);

		Product updated = service.updateProduct(5L, change);

		assertThat(updated.getQuantity()).isEqualTo(9);
		assertThat(updated.getDescription()).isEqualTo("old");
	}

	@Test
	void minPriceAndDiscountArePassedToTheQuery() {
		when(productRepository.filterProducts(anyString(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
		service.getAllProduct("men_jeans", List.of(), List.of(), 500, 1000, 20, "price_high", null, 0, 10);
		verify(productRepository).filterProducts("men_jeans", 500, 1000, 20, "price_high");
	}
}
