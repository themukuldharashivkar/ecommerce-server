package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.ProductException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.User;
import com.bazaarly.request.AddItemRequest;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.service.CartService;
import com.bazaarly.service.UserService;

@RestController
@Tag(name = "Cart")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequestMapping("/api/cart")
public class CartController {
	
	private CartService cartService;
	private UserService userService;
	
	public CartController(CartService cartService,UserService userService) {
		this.cartService=cartService;
		this.userService=userService;
	}
	
	@GetMapping("/")
	public ResponseEntity<Cart> findUserCartHandler(@RequestHeader("Authorization") String jwt) throws UserException{
		
		User user=userService.findUserProfileByJwt(jwt);
		
		Cart cart=cartService.findUserCart(user.getId());
		
		
		return new ResponseEntity<Cart>(cart,HttpStatus.OK);
	}
	
	@PutMapping("/add")
	public ResponseEntity<ApiResponse> addItemToCart(@Valid @RequestBody AddItemRequest req, @RequestHeader("Authorization") String jwt) throws UserException, ProductException{
		
		User user=userService.findUserProfileByJwt(jwt);
		
		cartService.addCartItem(user.getId(), req);
		
		ApiResponse res= new ApiResponse("Item Added To Cart Successfully",true);
		
		return new ResponseEntity<ApiResponse>(res,HttpStatus.ACCEPTED);
		
	}
	

}
