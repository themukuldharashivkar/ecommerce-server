package com.bazaarly.service;

import com.bazaarly.exception.CartItemException;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.Product;

public interface CartItemService {
	
	public CartItem createCartItem(CartItem cartItem);
	
	public CartItem updateCartItem(Long userId, Long id,CartItem cartItem) throws CartItemException, UserException;
	
	public CartItem isCartItemExist(Cart cart,Product product,String size, Long userId);
	
	public void removeCartItem(Long userId,Long cartItemId) throws CartItemException, UserException;
	
	public CartItem findCartItemById(Long cartItemId) throws CartItemException;
	
}
