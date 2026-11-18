package com.bazaarly.service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import com.bazaarly.request.ShippingAddressRequest;

import com.bazaarly.exception.OrderNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Address;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.OrderItem;
import com.bazaarly.modal.User;
import com.bazaarly.repository.AddressRepository;
import com.bazaarly.repository.OrderItemRepository;
import com.bazaarly.repository.OrderRepository;
import com.bazaarly.repository.UserRepository;
import com.bazaarly.user.domain.OrderStatus;
import com.bazaarly.user.domain.PaymentStatus;

@Service
public class OrderServiceImplementation implements OrderService {
	
	private OrderRepository orderRepository;
	private CartService cartService;
	private AddressRepository addressRepository;
	private UserRepository userRepository;
	private OrderItemService orderItemService;
	private OrderItemRepository orderItemRepository;
	private StockService stockService;
	
	public OrderServiceImplementation(OrderRepository orderRepository,CartService cartService,
			AddressRepository addressRepository,UserRepository userRepository,
			OrderItemService orderItemService,OrderItemRepository orderItemRepository,StockService stockService) {
		this.stockService=stockService;
		this.orderRepository=orderRepository;
		this.cartService=cartService;
		this.addressRepository=addressRepository;
		this.userRepository=userRepository;
		this.orderItemService=orderItemService;
		this.orderItemRepository=orderItemRepository;
	}

	@Override
	// one transaction: if any item is out of stock, nothing is reserved and no order or address is saved
	@Transactional(rollbackFor = Exception.class)
	public Order createOrder(User user, Address shippAddress) throws OrderException {
		
		Cart cart=cartService.findUserCart(user.getId());
		if (cart.getCartItems().isEmpty()) {
			throw new OrderException("Your cart is empty");
		}

		Address address;
		if (shippAddress.getId() != null) {
			address = shippAddress; // a saved address of this user, checked by resolveShippingAddress
		} else {
			shippAddress.setUser(user);
			address = addressRepository.save(shippAddress);
			user.getAddresses().add(address);
			userRepository.save(user);
		}

		List<OrderItem> orderItems=new ArrayList<>();
		
		for(CartItem item: cart.getCartItems()) {
			OrderItem orderItem=new OrderItem();
			
			orderItem.setPrice(item.getPrice());
			orderItem.setProduct(item.getProduct());
			orderItem.setQuantity(item.getQuantity());
			orderItem.setSize(item.getSize());
			orderItem.setUserId(item.getUserId());
			orderItem.setDiscountedPrice(item.getDiscountedPrice());
			
			
			OrderItem createdOrderItem=orderItemRepository.save(orderItem);
			
			orderItems.add(createdOrderItem);
		}

		// reserve the stock now; released again if the order is cancelled or expires unpaid
		stockService.reserve(orderItems);
		
		
		Order createdOrder=new Order();
		createdOrder.setUser(user);
		createdOrder.setOrderItems(orderItems);
		createdOrder.setTotalPrice(cart.getTotalPrice());
		createdOrder.setTotalDiscountedPrice(cart.getTotalDiscountedPrice());
		createdOrder.setDiscounte(cart.getDiscounte());
		createdOrder.setTotalItem(cart.getTotalItem());
		
		createdOrder.setShippingAddress(address);
		createdOrder.setOrderDate(LocalDateTime.now());
		createdOrder.setOrderStatus(OrderStatus.PENDING);
		createdOrder.getPaymentDetails().setStatus(PaymentStatus.PENDING);
		createdOrder.setCreatedAt(LocalDateTime.now());
		
		Order savedOrder=orderRepository.save(createdOrder);
		
		for(OrderItem item:orderItems) {
			item.setOrder(savedOrder);
			orderItemRepository.save(item);
		}
		
		return savedOrder;
		
	}

	@Override
	public Address resolveShippingAddress(User user, ShippingAddressRequest req) throws OrderException {
		if (req.id() != null) {
			// only the caller's own saved addresses can be reused
			return addressRepository.findById(req.id())
					.filter(a -> a.getUser() != null && a.getUser().getId().equals(user.getId()))
					.orElseThrow(() -> new OrderException("Saved address not found"));
		}
		Address address = new Address();
		address.setFirstName(req.firstName().trim());
		address.setLastName(req.lastName().trim());
		address.setStreetAddress(req.streetAddress().trim());
		address.setCity(req.city().trim());
		address.setState(req.state().trim());
		address.setZipCode(req.zipCode().trim());
		address.setMobile(req.mobile().trim());
		return address;
	}

	@Override
	public Order placedOrder(Long orderId) throws OrderException {
		Order order=findOrderById(orderId);
		order.setOrderStatus(OrderStatus.PLACED);
		order.getPaymentDetails().setStatus(PaymentStatus.COMPLETED);
		return order;
	}

	@Override
	public Order confirmedOrder(Long orderId) throws OrderException {
		Order order=findOrderById(orderId);
		order.setOrderStatus(OrderStatus.CONFIRMED);
		
		
		return orderRepository.save(order);
	}

	@Override
	public Order shippedOrder(Long orderId) throws OrderException {
		Order order=findOrderById(orderId);
		order.setOrderStatus(OrderStatus.SHIPPED);
		return orderRepository.save(order);
	}

	@Override
	public Order deliveredOrder(Long orderId) throws OrderException {
		Order order=findOrderById(orderId);
		order.setOrderStatus(OrderStatus.DELIVERED);
		return orderRepository.save(order);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Order cancledOrder(Long orderId) throws OrderException {
		Order order=orderRepository.findByIdForUpdate(orderId)
				.orElseThrow(() -> new OrderNotFoundException("order not exist with id "+orderId));
		if (order.getOrderStatus() == OrderStatus.CANCELLED) {
			return order; // already cancelled: stock was given back then
		}
		if (order.getOrderStatus() == OrderStatus.SHIPPED || order.getOrderStatus() == OrderStatus.DELIVERED) {
			throw new OrderException("Order " + orderId + " has already shipped and cannot be cancelled");
		}
		order.setOrderStatus(OrderStatus.CANCELLED);
		stockService.release(order.getOrderItems());
		return orderRepository.save(order);
	}

	@Override
	@Transactional
	public boolean expireIfUnpaid(Long orderId, LocalDateTime createdBefore) {
		Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
		if (order == null || order.getOrderStatus() != OrderStatus.PENDING
				|| order.getPaymentDetails().getStatus() == PaymentStatus.COMPLETED
				|| order.getCreatedAt() == null || !order.getCreatedAt().isBefore(createdBefore)) {
			return false; // paid, already handled, or not old enough (re-checked under the row lock)
		}
		order.setOrderStatus(OrderStatus.CANCELLED);
		stockService.release(order.getOrderItems());
		orderRepository.save(order);
		return true;
	}

	@Override
	public Order findOrderById(Long orderId) throws OrderException {
		Optional<Order> opt=orderRepository.findById(orderId);
		
		if(opt.isPresent()) {
			return opt.get();
		}
		throw new OrderNotFoundException("order not exist with id "+orderId);
	}

	@Override
	public Page<Order> usersOrderHistory(Long userId, Pageable pageable) {
		return orderRepository.getUsersOrders(userId, pageable);
	}

	@Override
	public Page<Order> getAllOrders(Pageable pageable) {
		return orderRepository.findAll(pageable);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteOrder(Long orderId) throws OrderException {
		Order order =findOrderById(orderId);
		// an order that still holds stock gives it back before it disappears
		if (order.getOrderStatus() == OrderStatus.PENDING || order.getOrderStatus() == OrderStatus.PLACED
				|| order.getOrderStatus() == OrderStatus.CONFIRMED) {
			stockService.release(order.getOrderItems());
		}
		orderRepository.deleteById(orderId);
		
	}

}
