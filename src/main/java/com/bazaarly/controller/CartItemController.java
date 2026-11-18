package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import jakarta.validation.Valid;
import com.bazaarly.request.UpdateCartItemRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.CartItemException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.User;
import com.bazaarly.response.ApiResponse;
import com.bazaarly.service.CartItemService;
import com.bazaarly.service.UserService;

@RestController
@Tag(name = "Cart")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequestMapping("/api/cart_items")
public class CartItemController {

	private CartItemService cartItemService;
	private UserService userService;
	
	public CartItemController(CartItemService cartItemService,UserService userService) {
		this.cartItemService=cartItemService;
		this.userService=userService;
	}
	
	@DeleteMapping("/{cartItemId}")
	public ResponseEntity<ApiResponse>deleteCartItemHandler(@PathVariable Long cartItemId, @RequestHeader("Authorization")String jwt) throws CartItemException, UserException{
		
		User user=userService.findUserProfileByJwt(jwt);
		cartItemService.removeCartItem(user.getId(), cartItemId);
		
		ApiResponse res=new ApiResponse("Item Remove From Cart",true);
		
		return new ResponseEntity<ApiResponse>(res,HttpStatus.ACCEPTED);
	}
	
	@PutMapping("/{cartItemId}")
	public ResponseEntity<CartItem>updateCartItemHandler(@PathVariable Long cartItemId, @Valid @RequestBody UpdateCartItemRequest req, @RequestHeader("Authorization")String jwt) throws CartItemException, UserException{
		
		User user=userService.findUserProfileByJwt(jwt);
		
		CartItem change = new CartItem();
		change.setQuantity(req.quantity());
		CartItem updatedCartItem =cartItemService.updateCartItem(user.getId(), cartItemId, change);
		
		//ApiResponse res=new ApiResponse("Item Updated",true);
		
		return new ResponseEntity<>(updatedCartItem,HttpStatus.ACCEPTED);
	}
}
