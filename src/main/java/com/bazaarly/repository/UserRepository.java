package com.bazaarly.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.bazaarly.modal.User;

public interface UserRepository extends JpaRepository<User, Long> {
	
	public User findByEmail(String email);

}
