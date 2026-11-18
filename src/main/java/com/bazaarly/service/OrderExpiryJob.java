package com.bazaarly.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bazaarly.repository.OrderRepository;

/**
 * Gives back the stock of orders that were never paid. An order reserves stock when it is placed;
 * its Razorpay link expires after app.orders.reservation-ttl, and a short grace period later this job
 * cancels the order and releases the stock (late confirmations still in flight get the grace period).
 */
@Component
public class OrderExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

	private final OrderRepository orderRepository;
	private final OrderService orderService;
	private final Duration reservationTtl;
	private final Duration grace;

	public OrderExpiryJob(OrderRepository orderRepository, OrderService orderService,
			@Value("${app.orders.reservation-ttl}") Duration reservationTtl,
			@Value("${app.orders.expiry-grace}") Duration grace) {
		this.orderRepository = orderRepository;
		this.orderService = orderService;
		this.reservationTtl = reservationTtl;
		this.grace = grace;
	}

	@Scheduled(fixedDelayString = "${app.orders.expiry-sweep-interval}", initialDelayString = "${app.orders.expiry-sweep-interval}")
	public int expireUnpaidOrders() {
		LocalDateTime cutoff = LocalDateTime.now().minus(reservationTtl).minus(grace);
		List<Long> candidates = orderRepository.findUnpaidCreatedBefore(cutoff);
		int expired = 0;
		for (Long orderId : candidates) {
			try {
				if (orderService.expireIfUnpaid(orderId, cutoff)) {
					expired++;
				}
			} catch (RuntimeException e) {
				log.error("Could not expire order {}: {}", orderId, e.getMessage());
			}
		}
		if (expired > 0) {
			log.info("Expired {} unpaid order(s) and released their stock", expired);
		}
		return expired;
	}
}
