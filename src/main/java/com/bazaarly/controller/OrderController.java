package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import com.bazaarly.exception.OrderNotFoundException;

import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Max;

import org.springframework.validation.annotation.Validated;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

import java.util.List;

import jakarta.validation.Valid;
import com.bazaarly.request.ShippingAddressRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.OrderException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.Address;
import com.bazaarly.modal.Order;
import com.bazaarly.modal.User;
import com.bazaarly.service.OrderService;
import com.bazaarly.service.UserService;

@RestController
@Tag(name = "Orders")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
@RequestMapping("/api/orders")
public class OrderController {
	
	private OrderService orderService;
	private UserService userService;
	
	public OrderController(OrderService orderService,UserService userService) {
		this.orderService=orderService;
		this.userService=userService;
	}
	
	@PostMapping("/")
	public ResponseEntity<Order> createOrderHandler(@Valid @RequestBody ShippingAddressRequest shippingAddress,
			@RequestHeader("Authorization")String jwt) throws UserException, OrderException{
		
		User user=userService.findUserProfileByJwt(jwt);
		Order order =orderService.createOrder(user, orderService.resolveShippingAddress(user, shippingAddress));
		
		return new ResponseEntity<Order>(order,HttpStatus.OK);
		
	}
	
	@GetMapping("/user")
	public ResponseEntity< List<Order>> usersOrderHistoryHandler(@RequestHeader("Authorization") 
	String jwt, @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or more") int page,
			@RequestParam(defaultValue = "50") @Min(value = 1, message = "size must be at least 1") @Max(value = 100, message = "size can be at most 100") int size) throws OrderException, UserException{
		
		User user=userService.findUserProfileByJwt(jwt);
		// newest first; X-Total-Count has the total
		return Paged.list(orderService.usersOrderHistory(user.getId(), PageRequest.of(page, size, Sort.by(Sort.Order.desc("id")))), HttpStatus.ACCEPTED);
	}
	
	@GetMapping("/{orderId}")
	public ResponseEntity< Order> findOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") 
	String jwt) throws OrderException, UserException{
		
		User user=userService.findUserProfileByJwt(jwt);
		Order orders=orderService.findOrderById(orderId);
		// only the buyer may read an order (admins use /api/admin/orders); others get the same 404 as an unknown id
		if (orders.getUser() == null || !orders.getUser().getId().equals(user.getId())) {
			throw new OrderNotFoundException("order not exist with id " + orderId);
		}
		return new ResponseEntity<>(orders,HttpStatus.ACCEPTED);
	}

}
