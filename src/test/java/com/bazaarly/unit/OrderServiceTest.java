package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bazaarly.exception.OrderException;
import com.bazaarly.exception.OrderNotFoundException;
import com.bazaarly.request.ShippingAddressRequest;
import com.bazaarly.modal.Address;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.OrderItem;
import com.bazaarly.modal.Product;
import com.bazaarly.modal.User;
import com.bazaarly.repository.AddressRepository;
import com.bazaarly.repository.OrderItemRepository;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.repository.UserRepository;
import com.bazaarly.service.CartService;
import com.bazaarly.service.OrderItemService;
import com.bazaarly.service.OrderServiceImplementation;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	@Mock OrderRepository orderRepository;
	@Mock CartService cartService;
	@Mock AddressRepository addressRepository;
	@Mock UserRepository userRepository;
	@Mock OrderItemService orderItemService;
	@Mock OrderItemRepository orderItemRepository;

	OrderServiceImplementation service;

	@BeforeEach
	void setUp() {
		service = new OrderServiceImplementation(orderRepository, cartService, addressRepository, userRepository,
				orderItemService, orderItemRepository);
	}

	private static CartItem cartItem(String size, int quantity, int price, int discountedPrice) {
		CartItem item = new CartItem();
		Product product = new Product();
		product.setId((long) price);
		item.setProduct(product);
		item.setSize(size);
		item.setQuantity(quantity);
		item.setPrice(price);
		item.setDiscountedPrice(discountedPrice);
		item.setUserId(1L);
		return item;
	}

	@Test
	void createOrderCopiesTheCartIntoAPendingOrder() throws Exception {
		User user = new User();
		user.setId(1L);
		Address address = new Address();
		Cart cart = new Cart();
		cart.getCartItems().add(cartItem("M", 1, 1000, 700));
		cart.getCartItems().add(cartItem("L", 2, 4000, 3000));
		cart.setTotalPrice(5000);
		cart.setTotalDiscountedPrice(3700);
		cart.setDiscounte(1300);
		cart.setTotalItem(3);
		when(addressRepository.save(address)).thenReturn(address);
		when(cartService.findUserCart(1L)).thenReturn(cart);
		when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Order order = service.createOrder(user, address);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentDetails().getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(order.getUser()).isSameAs(user);
		assertThat(order.getShippingAddress()).isSameAs(address);
		assertThat(address.getUser()).isSameAs(user);
		assertThat(order.getTotalPrice()).isEqualTo(5000);
		assertThat(order.getTotalDiscountedPrice()).isEqualTo(3700);
		assertThat(order.getDiscounte()).isEqualTo(1300);
		assertThat(order.getTotalItem()).isEqualTo(3);
		assertThat(order.getOrderDate()).isNotNull();
		assertThat(order.getOrderItems()).hasSize(2)
				.extracting(OrderItem::getSize).containsExactlyInAnyOrder("M", "L");
		// every order item ends up linked to the saved order
		assertThat(order.getOrderItems()).allSatisfy(item -> assertThat(item.getOrder()).isSameAs(order));
		verify(orderItemRepository, times(4)).save(any()); // saved once, then again with the order link
	}

	@Test
	void orderFromAnEmptyCartIsRejected() {
		User user = new User();
		user.setId(1L);
		when(cartService.findUserCart(1L)).thenReturn(new Cart());

		assertThatThrownBy(() -> service.createOrder(user, new Address()))
				.isInstanceOf(OrderException.class).hasMessage("Your cart is empty");
		verify(orderRepository, never()).save(any());
		verify(addressRepository, never()).save(any());
	}

	@Test
	void savedAddressOfTheCallerIsReusedWithoutSavingItAgain() throws Exception {
		User user = new User();
		user.setId(1L);
		Address saved = new Address();
		saved.setId(50L);
		saved.setUser(user);
		when(addressRepository.findById(50L)).thenReturn(Optional.of(saved));

		Address resolved = service.resolveShippingAddress(user,
				new ShippingAddressRequest(50L, null, null, null, null, null, null, null));

		assertThat(resolved).isSameAs(saved);
	}

	@Test
	void anotherUsersSavedAddressCannotBeUsed() {
		User me = new User();
		me.setId(1L);
		User other = new User();
		other.setId(2L);
		Address theirs = new Address();
		theirs.setId(60L);
		theirs.setUser(other);
		when(addressRepository.findById(60L)).thenReturn(Optional.of(theirs));

		assertThatThrownBy(() -> service.resolveShippingAddress(me,
				new ShippingAddressRequest(60L, null, null, null, null, null, null, null)))
				.isInstanceOf(OrderException.class).hasMessage("Saved address not found");
	}

	@Test
	void newAddressIsBuiltFromTheTrimmedRequest() throws Exception {
		Address address = service.resolveShippingAddress(new User(),
				new ShippingAddressRequest(null, " Asha ", "Rao", "12 MG Road", "Pune", "MH", "411001", "9876543210"));

		assertThat(address.getId()).isNull();
		assertThat(address.getFirstName()).isEqualTo("Asha");
		assertThat(address.getZipCode()).isEqualTo("411001");
	}

	@Test
	void findOrderByIdOfAnUnknownOrderFails() {
		when(orderRepository.findById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findOrderById(9L))
				.isInstanceOf(OrderNotFoundException.class)
				.hasMessage("order not exist with id 9");
	}

	@Test
	void adminStatusChangesAreSaved() throws Exception {
		Order order = new Order();
		when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
		when(orderRepository.save(order)).thenReturn(order);

		assertThat(service.confirmedOrder(3L).getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(service.shippedOrder(3L).getOrderStatus()).isEqualTo(OrderStatus.SHIPPED);
		assertThat(service.deliveredOrder(3L).getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(service.cancledOrder(3L).getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
		verify(orderRepository, times(4)).save(order);
	}

	@Test
	void statusChangeOnAnUnknownOrderFailsWithoutSaving() {
		when(orderRepository.findById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.shippedOrder(9L)).isInstanceOf(OrderException.class);
		verify(orderRepository, never()).save(any());
	}

	@Test
	void deleteOrderOfAnUnknownOrderFailsWithoutDeleting() {
		when(orderRepository.findById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteOrder(9L)).isInstanceOf(OrderException.class);
		verify(orderRepository, never()).deleteById(any());
	}
}
