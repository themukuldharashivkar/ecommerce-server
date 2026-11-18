package com.bazaarly.service;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import com.bazaarly.request.ShippingAddressRequest;

import java.util.List;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Address;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;

public interface OrderService {
	
	public Order createOrder(User user, Address shippingAdress) throws OrderException;

	/** The caller's saved address (when an id is given) or a new, unsaved address built from the request. */
	public Address resolveShippingAddress(User user, ShippingAddressRequest req) throws OrderException;
	
	public Order findOrderById(Long orderId) throws OrderException;
	
	public Page<Order> usersOrderHistory(Long userId, Pageable pageable);
	
	public Order placedOrder(Long orderId) throws OrderException;
	
	public Order confirmedOrder(Long orderId)throws OrderException;
	
	public Order shippedOrder(Long orderId) throws OrderException;
	
	public Order deliveredOrder(Long orderId) throws OrderException;
	
	public Order cancledOrder(Long orderId) throws OrderException;
	
	public Page<Order> getAllOrders(Pageable pageable);
	
	public void deleteOrder(Long orderId) throws OrderException;
	
}
