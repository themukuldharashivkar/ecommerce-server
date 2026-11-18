package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Max;

import org.springframework.validation.annotation.Validated;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.OrderException;
import com.bazaarly.modal.Order;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.service.OrderService;

@RestController
@Tag(name = "Admin: orders")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
	
	private OrderService orderService;
	
	public AdminOrderController(OrderService orderService) {
		this.orderService=orderService;
	}
	
	@GetMapping("/")
	public ResponseEntity<List<Order>> getAllOrdersHandler(@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or more") int page,
			@RequestParam(defaultValue = "50") @Min(value = 1, message = "size must be at least 1") @Max(value = 100, message = "size can be at most 100") int size){
		return Paged.list(orderService.getAllOrders(PageRequest.of(page, size, Sort.by(Sort.Order.desc("id")))), HttpStatus.ACCEPTED);
	}
	
	@PutMapping("/{orderId}/confirmed")
	public ResponseEntity<Order> ConfirmedOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") String jwt) throws OrderException{
		Order order=orderService.confirmedOrder(orderId);
		return new ResponseEntity<Order>(order,HttpStatus.ACCEPTED);
	}
	
	@PutMapping("/{orderId}/ship")
	public ResponseEntity<Order> shippedOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") String jwt) throws OrderException{
		Order order=orderService.shippedOrder(orderId);
		return new ResponseEntity<Order>(order,HttpStatus.ACCEPTED);
	}
	
	@PutMapping("/{orderId}/deliver")
	public ResponseEntity<Order> deliveredOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") String jwt) throws OrderException{
		Order order=orderService.deliveredOrder(orderId);
		return new ResponseEntity<Order>(order,HttpStatus.ACCEPTED);
	}
	
	@PutMapping("/{orderId}/cancel")
	public ResponseEntity<Order> canceledOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") String jwt) throws OrderException{
		Order order=orderService.cancledOrder(orderId);
		return new ResponseEntity<Order>(order,HttpStatus.ACCEPTED);
	}
	
	@DeleteMapping("/{orderId}/delete")
	public ResponseEntity<ApiResponse> deleteOrderHandler(@PathVariable Long orderId, @RequestHeader("Authorization") String jwt) throws OrderException{
		orderService.deleteOrder(orderId);
		ApiResponse res=new ApiResponse("Order Deleted Successfully",true);
		return new ResponseEntity<>(res,HttpStatus.ACCEPTED);
	}

}
