package com.bazaarly.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bazaarly.modal.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

}
