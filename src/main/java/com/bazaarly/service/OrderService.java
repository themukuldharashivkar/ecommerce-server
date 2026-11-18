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

	/** Cancels the order and gives its stock back if it is still unpaid and was created before the cutoff. */
	public boolean expireIfUnpaid(Long orderId, java.time.LocalDateTime createdBefore);

	/**
	 * Cancels the order and gives its stock back. A paid order must already be refunded: pass the refund id
	 * (PaymentService.cancelOrder does the refund first). Cancelling twice is a no-op.
	 */
	public Order cancelOrder(Long orderId, String refundId) throws OrderException;

	/** Records a refund on an order that is already cancelled (a payment that arrived after it expired). */
	public void recordRefundOnCancelledOrder(Long orderId, String paymentId, String refundId);
	
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
