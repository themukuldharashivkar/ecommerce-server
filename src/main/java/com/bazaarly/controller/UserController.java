package com.bazaarly.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bazaarly.config.OpenApiConfig;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bazaarly.exception.UserException;
import com.bazaarly.modal.User;
import com.bazaarly.service.UserService;

@RestController
@Tag(name = "Users")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RequestMapping("/api/users")
public class UserController {
	
	private UserService userService;
	
	public UserController(UserService userService) {
		this.userService=userService;
	}
	
	@GetMapping("/profile")
	public ResponseEntity<User> getUserProfileHandler(@RequestHeader("Authorization") String jwt) throws UserException{

		User user=userService.findUserProfileByJwt(jwt);
		return new ResponseEntity<User>(user,HttpStatus.ACCEPTED);
	}

}
