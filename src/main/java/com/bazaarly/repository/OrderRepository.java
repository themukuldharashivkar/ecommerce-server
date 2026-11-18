package com.bazaarly.repository;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bazaarly.modal.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** Loads the order with a row lock (SELECT ... FOR UPDATE) so concurrent payment confirmations run one at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id") Long id);

	/** Unpaid orders older than the cutoff (candidates for expiry; re-checked under a lock). */
	@Query("SELECT o.id FROM Order o WHERE o.orderStatus = com.bazaarly.user.domain.OrderStatus.PENDING "
			+ "AND o.paymentDetails.status = com.bazaarly.user.domain.PaymentStatus.PENDING AND o.createdAt < :cutoff")
	List<Long> findUnpaidCreatedBefore(@Param("cutoff") java.time.LocalDateTime cutoff);

	@Query("SELECT o FROM Order o WHERE o.user.id = :userId AND (o.orderStatus = PLACED OR o.orderStatus = CONFIRMED OR o.orderStatus = SHIPPED OR o.orderStatus = DELIVERED)")
	public Page<Order> getUsersOrders(@Param("userId") Long userId, Pageable pageable);
}
