package com.bazaarly.repository;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Page;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bazaarly.modal.Order;

/** findByIdForUpdate (row lock) is in {@link OrderRepositoryCustom}. */
public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {

	/** Unpaid orders older than the cutoff (candidates for expiry; re-checked under a lock). */
	@Query("SELECT o.id FROM Order o WHERE o.orderStatus = com.bazaarly.user.domain.OrderStatus.PENDING "
			+ "AND o.paymentDetails.status = com.bazaarly.user.domain.PaymentStatus.PENDING AND o.createdAt < :cutoff")
	List<Long> findUnpaidCreatedBefore(@Param("cutoff") java.time.LocalDateTime cutoff);

	@Query("SELECT o FROM Order o WHERE o.user.id = :userId AND (o.orderStatus = PLACED OR o.orderStatus = CONFIRMED OR o.orderStatus = SHIPPED OR o.orderStatus = DELIVERED)")
	public Page<Order> getUsersOrders(@Param("userId") Long userId, Pageable pageable);
}
