package com.bazaarly.config;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bazaarly.modal.User;
import com.bazaarly.repository.UserRepository;
import com.bazaarly.service.CartService;
import com.bazaarly.user.domain.UserRole;

/**
 * Admins cannot sign up through the API. The admin account comes from ADMIN_EMAIL / ADMIN_PASSWORD:
 * on startup it is created if missing, or promoted to ROLE_ADMIN if a user with that email already exists.
 * An existing account keeps its password. Nothing happens when the variables are not set.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
	private static final int MIN_PASSWORD_LENGTH = 8;

	private final UserRepository userRepository;
	private final CartService cartService;
	private final PasswordEncoder passwordEncoder;
	private final String adminEmail;
	private final String adminPassword;

	public AdminBootstrap(UserRepository userRepository, CartService cartService, PasswordEncoder passwordEncoder,
			@Value("${app.admin.email:}") String adminEmail, @Value("${app.admin.password:}") String adminPassword) {
		this.userRepository = userRepository;
		this.cartService = cartService;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
	}

	@Override
	public void run(String... args) {
		if (!StringUtils.hasText(adminEmail)) {
			log.info("ADMIN_EMAIL not set: no admin account bootstrapped");
			return;
		}
		User existing = userRepository.findByEmail(adminEmail);
		if (existing != null) {
			if (existing.getRole() != UserRole.ROLE_ADMIN) {
				existing.setRole(UserRole.ROLE_ADMIN);
				userRepository.save(existing);
				log.info("Promoted existing user to admin");
			}
			return;
		}
		if (adminPassword == null || adminPassword.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters to create the admin account");
		}
		User admin = new User();
		admin.setEmail(adminEmail);
		admin.setFirstName("Admin");
		admin.setLastName("");
		admin.setPassword(passwordEncoder.encode(adminPassword));
		admin.setRole(UserRole.ROLE_ADMIN);
		admin.setCreatedAt(LocalDateTime.now());
		User saved = userRepository.save(admin);
		cartService.createCart(saved);
		log.info("Created admin account");
	}
}
