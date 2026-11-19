package com.bazaarly.repository;

import java.util.Optional;

import com.bazaarly.modal.Order;

public interface OrderRepositoryCustom {

	/**
	 * Locks the order row (SELECT ... FOR UPDATE) and returns the order as it is in the database once the lock
	 * is held. Must run inside a transaction; the lock is kept until it ends. Empty if the order does not exist
	 * (also when it was deleted while this call waited for the lock).
	 */
	Optional<Order> findByIdForUpdate(Long id);
}
