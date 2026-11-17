package com.bazaarly.service;

import com.bazaarly.exception.UserException;
import com.bazaarly.modal.User;

public interface UserService {
	
	public User findUserById(Long userId) throws UserException;
	
	public User findUserProfileByJwt(String jwt) throws UserException;

}
