package com.bazaarly.repository;

import java.util.Optional;

import com.bazaarly.modal.Order;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * A plain JPA lock query is not enough here. With open-in-view, a web request keeps one persistence context,
 * and the services usually load the order before locking it. Hibernate then returns that already loaded
 * instance with its old state, even though the locked row may have changed (cancelled, expired or paid by
 * another request). That made a concurrent cancel and expiry both give the stock back, and let a payment
 * mark an expired order paid. So the row is locked first, and the order is then refreshed from it.
 */
class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public Optional<Order> findByIdForUpdate(Long id) {
		boolean exists = !entityManager.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE")
				.setParameter("id", id).getResultList().isEmpty();
		if (!exists) {
			return Optional.empty();
		}
		Order order = entityManager.find(Order.class, id);
		entityManager.refresh(order); // the row is locked: this is its current state, and it cannot change until commit
		return Optional.of(order);
	}
}
