package com.bazaarly.notification;

import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bazaarly.modal.Address;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.OrderItem;
import com.bazaarly.repository.OrderRepository;

/** Builds the plain-text confirmation from the stored order (inside a transaction, for lazy items). */
@Component
public class OrderConfirmationMessageBuilder {

	private final OrderRepository orderRepository;

	public OrderConfirmationMessageBuilder(OrderRepository orderRepository) {
		this.orderRepository = orderRepository;
	}

	@Transactional(readOnly = true)
	public OrderConfirmationMessage build(long orderId) {
		Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new IllegalStateException("order " + orderId + " no longer exists"));

		StringBuilder body = new StringBuilder();
		body.append("Hi ").append(order.getUser().getFirstName()).append(",\n\n")
				.append("Thanks for shopping with Bazaarly. Your payment was received and order #")
				.append(order.getId()).append(" is placed.\n\n");
		for (OrderItem item : order.getOrderItems()) {
			body.append(String.format(Locale.ROOT, "  %d x %s (size %s)  Rs %d%n", item.getQuantity(),
					item.getProduct().getTitle(), item.getSize(), item.getDiscountedPrice()));
		}
		body.append(String.format(Locale.ROOT, "%nTotal paid: Rs %d%n", order.getTotalDiscountedPrice()));
		Address address = order.getShippingAddress();
		if (address != null) {
			body.append("\nShipping to:\n  ").append(address.getFirstName()).append(' ').append(address.getLastName())
					.append("\n  ").append(address.getStreetAddress())
					.append("\n  ").append(address.getCity()).append(", ").append(address.getState()).append(' ')
					.append(address.getZipCode()).append('\n');
		}
		body.append("\nThis is a test-mode store: no real money was charged.\n");

		return new OrderConfirmationMessage(order.getId(), order.getUser().getEmail(),
				"Bazaarly order #" + order.getId() + " confirmed", body.toString());
	}
}
