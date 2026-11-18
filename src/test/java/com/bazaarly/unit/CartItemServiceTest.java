package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bazaarly.exception.CartItemException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.User;
import com.bazaarly.repository.CartItemRepository;
import com.bazaarly.service.CartItemServiceImplementation;
import com.bazaarly.service.UserService;

@ExtendWith(MockitoExtension.class)
class CartItemServiceTest {

	@Mock CartItemRepository cartItemRepository;
	@Mock UserService userService;

	CartItemServiceImplementation service;
	CartItem item;

	@BeforeEach
	void setUp() {
		service = new CartItemServiceImplementation(cartItemRepository, userService);
		Product product = new Product();
		product.setPrice(1000);
		product.setDiscountedPrice(700);
		item = new CartItem();
		item.setId(11L);
		item.setProduct(product);
		item.setUserId(1L);
		item.setQuantity(1);
	}

	private static User user(long id) {
		User user = new User();
		user.setId(id);
		return user;
	}

	@Test
	void createCartItemPricesTheLineFromTheProduct() {
		when(cartItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		CartItem created = service.createCartItem(item);

		assertThat(created.getQuantity()).isEqualTo(1);
		assertThat(created.getPrice()).isEqualTo(1000);
		assertThat(created.getDiscountedPrice()).isEqualTo(700);
	}

	@Test
	void createCartItemKeepsTheRequestedQuantity() {
		// regression: the quantity used to be forced to 1
		item.setQuantity(3);
		when(cartItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		CartItem created = service.createCartItem(item);

		assertThat(created.getQuantity()).isEqualTo(3);
		assertThat(created.getPrice()).isEqualTo(3000);
		assertThat(created.getDiscountedPrice()).isEqualTo(2100);
	}

	@Test
	void ownerCanChangeQuantityAndPricesAreRecalculated() throws Exception {
		CartItem change = new CartItem();
		change.setQuantity(3);
		when(cartItemRepository.findById(11L)).thenReturn(Optional.of(item));
		when(userService.findUserById(1L)).thenReturn(user(1));
		when(cartItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		CartItem updated = service.updateCartItem(1L, 11L, change);

		assertThat(updated.getQuantity()).isEqualTo(3);
		assertThat(updated.getPrice()).isEqualTo(3000);
		assertThat(updated.getDiscountedPrice()).isEqualTo(2100);
	}

	@Test
	void anotherUserCannotChangeTheItem() throws Exception {
		when(cartItemRepository.findById(11L)).thenReturn(Optional.of(item));
		when(userService.findUserById(1L)).thenReturn(user(1));

		assertThatThrownBy(() -> service.updateCartItem(2L, 11L, new CartItem()))
				.isInstanceOf(CartItemException.class);
		verify(cartItemRepository, never()).save(any());
	}

	@Test
	void ownerCanRemoveTheItem() throws Exception {
		when(cartItemRepository.findById(11L)).thenReturn(Optional.of(item));
		when(userService.findUserById(1L)).thenReturn(user(1));

		service.removeCartItem(1L, 11L);

		verify(cartItemRepository).deleteById(11L);
	}

	@Test
	void anotherUserCannotRemoveTheItem() throws Exception {
		when(cartItemRepository.findById(11L)).thenReturn(Optional.of(item));
		when(userService.findUserById(1L)).thenReturn(user(1));
		when(userService.findUserById(2L)).thenReturn(user(2));

		assertThatThrownBy(() -> service.removeCartItem(2L, 11L)).isInstanceOf(UserException.class);
		verify(cartItemRepository, never()).deleteById(any());
	}

	@Test
	void unknownItemIsReported() {
		when(cartItemRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findCartItemById(99L))
				.isInstanceOf(com.bazaarly.exception.CartItemNotFoundException.class)
				.hasMessageContaining("99");
	}
}
