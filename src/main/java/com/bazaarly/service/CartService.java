package com.bazaarly.service;

import com.bazaarly.exception.ProductException;
import com.bazaarly.modal.Cart;
import com.bazaarly.modal.CartItem;
import com.bazaarly.modal.User;
import com.bazaarly.request.AddItemRequest;

public interface CartService {
	
	public Cart createCart(User user);
	
	public String addCartItem(Long userId,AddItemRequest req) throws ProductException;
	
	public Cart findUserCart(Long userId);

}
