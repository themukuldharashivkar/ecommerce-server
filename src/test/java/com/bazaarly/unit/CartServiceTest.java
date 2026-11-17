package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.User;
import com.bazaarly.repository.CartRepository;
import com.bazaarly.request.AddItemRequest;
import com.bazaarly.service.CartItemService;
import com.bazaarly.service.CartServiceImplementation;
import com.bazaarly.service.ProductService;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

	@Mock CartRepository cartRepository;
	@Mock CartItemService cartItemService;
	@Mock ProductService productService;

	CartServiceImplementation service;
	Cart cart;

	@BeforeEach
	void setUp() {
		service = new CartServiceImplementation(cartRepository, cartItemService, productService);
		cart = new Cart();
	}

	private static Product product(long id, int price, int discountedPrice) {
		Product p = new Product();
		p.setId(id);
		p.setPrice(price);
		p.setDiscountedPrice(discountedPrice);
		return p;
	}

	private static CartItem item(Product product, int quantity, int price, int discountedPrice) {
		CartItem item = new CartItem();
		item.setProduct(product);
		item.setSize("M");
		item.setQuantity(quantity);
		item.setPrice(price);
		item.setDiscountedPrice(discountedPrice);
		return item;
	}

	@Test
	void createCartLinksTheCartToTheUser() {
		User user = new User();
		when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Cart created = service.createCart(user);

		assertThat(created.getUser()).isSameAs(user);
	}

	@Test
	void findUserCartRecalculatesTotalsFromItems() {
		cart.getCartItems().add(item(product(1, 1000, 700), 2, 2000, 1400));
		cart.getCartItems().add(item(product(2, 500, 450), 1, 500, 450));
		when(cartRepository.findByUserId(7L)).thenReturn(cart);
		when(cartRepository.save(cart)).thenReturn(cart);

		Cart result = service.findUserCart(7L);

		assertThat(result.getTotalPrice()).isEqualTo(2500);
		assertThat(result.getTotalDiscountedPrice()).isEqualTo(1850);
		assertThat(result.getDiscounte()).isEqualTo(650);
		assertThat(result.getTotalItem()).isEqualTo(3); // sum of quantities
	}

	@Test
	void findUserCartOfAnEmptyCartIsAllZero() {
		when(cartRepository.findByUserId(7L)).thenReturn(cart);
		when(cartRepository.save(cart)).thenReturn(cart);

		Cart result = service.findUserCart(7L);

		assertThat(result.getTotalPrice()).isZero();
		assertThat(result.getTotalItem()).isZero();
		assertThat(result.getDiscounte()).isZero();
	}

	@Test
	void addCartItemCreatesANewLineWhenTheProductAndSizeAreNotInTheCart() throws Exception {
		Product product = product(5, 1000, 800);
		AddItemRequest req = new AddItemRequest();
		req.setProductId(5L);
		req.setSize("L");
		req.setQuantity(1);
		when(cartRepository.findByUserId(7L)).thenReturn(cart);
		when(productService.findProductById(5L)).thenReturn(product);
		when(cartItemService.isCartItemExist(cart, product, "L", 7L)).thenReturn(null);
		when(cartItemService.createCartItem(any())).thenAnswer(inv -> inv.getArgument(0));

		service.addCartItem(7L, req);

		ArgumentCaptor<CartItem> created = ArgumentCaptor.forClass(CartItem.class);
		verify(cartItemService).createCartItem(created.capture());
		assertThat(created.getValue().getProduct()).isSameAs(product);
		assertThat(created.getValue().getSize()).isEqualTo("L");
		assertThat(created.getValue().getUserId()).isEqualTo(7L);
		assertThat(created.getValue().getCart()).isSameAs(cart);
		assertThat(cart.getCartItems()).hasSize(1);
	}

	@Test
	void addCartItemDoesNothingWhenTheLineAlreadyExists() throws Exception {
		Product product = product(5, 1000, 800);
		AddItemRequest req = new AddItemRequest();
		req.setProductId(5L);
		req.setSize("L");
		req.setQuantity(1);
		when(cartRepository.findByUserId(7L)).thenReturn(cart);
		when(productService.findProductById(5L)).thenReturn(product);
		when(cartItemService.isCartItemExist(cart, product, "L", 7L)).thenReturn(new CartItem());

		service.addCartItem(7L, req);

		verify(cartItemService, never()).createCartItem(any());
	}

	@Test
	void addCartItemForAnUnknownProductFails() throws Exception {
		AddItemRequest req = new AddItemRequest();
		req.setProductId(404L);
		when(cartRepository.findByUserId(7L)).thenReturn(cart);
		when(productService.findProductById(404L)).thenThrow(new ProductException("product not found with id 404"));

		assertThatThrownBy(() -> service.addCartItem(7L, req)).isInstanceOf(ProductException.class);
		verify(cartItemService, never()).createCartItem(any());
	}
}
