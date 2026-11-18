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
 * Accounts that cannot be created through signup, set up on startup from configuration:
 * <ul>
 * <li>the admin (ADMIN_EMAIL / ADMIN_PASSWORD), ROLE_ADMIN</li>
 * <li>optional public demo accounts for reviewers: a shopper (DEMO_SHOPPER_EMAIL / _PASSWORD, ROLE_USER) and a
 * read-only admin (DEMO_ADMIN_EMAIL / _PASSWORD, ROLE_ADMIN_VIEWER) that can view the admin panel but change nothing</li>
 * </ul>
 * A missing account is created; an existing one gets the configured role and keeps its password.
 * An account whose email is not set is skipped.
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
	private final String demoShopperEmail;
	private final String demoShopperPassword;
	private final String demoAdminEmail;
	private final String demoAdminPassword;

	public AdminBootstrap(UserRepository userRepository, CartService cartService, PasswordEncoder passwordEncoder,
			@Value("${app.admin.email:}") String adminEmail, @Value("${app.admin.password:}") String adminPassword) {
		this(userRepository, cartService, passwordEncoder, adminEmail, adminPassword, "", "", "", "");
	}

	@org.springframework.beans.factory.annotation.Autowired
	public AdminBootstrap(UserRepository userRepository, CartService cartService, PasswordEncoder passwordEncoder,
			@Value("${app.admin.email:}") String adminEmail, @Value("${app.admin.password:}") String adminPassword,
			@Value("${app.demo.shopper.email:}") String demoShopperEmail,
			@Value("${app.demo.shopper.password:}") String demoShopperPassword,
			@Value("${app.demo.admin.email:}") String demoAdminEmail,
			@Value("${app.demo.admin.password:}") String demoAdminPassword) {
		this.userRepository = userRepository;
		this.cartService = cartService;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
		this.demoShopperEmail = demoShopperEmail;
		this.demoShopperPassword = demoShopperPassword;
		this.demoAdminEmail = demoAdminEmail;
		this.demoAdminPassword = demoAdminPassword;
	}

	@Override
	public void run(String... args) {
		if (!StringUtils.hasText(adminEmail)) {
			log.info("ADMIN_EMAIL not set: no admin account bootstrapped");
		}
		ensureAccount(adminEmail, adminPassword, UserRole.ROLE_ADMIN, "Admin", "admin");
		ensureAccount(demoShopperEmail, demoShopperPassword, UserRole.ROLE_USER, "Demo", "demo shopper");
		ensureAccount(demoAdminEmail, demoAdminPassword, UserRole.ROLE_ADMIN_VIEWER, "Demo admin", "read-only demo admin");
	}

	private void ensureAccount(String rawEmail, String password, UserRole role, String firstName, String label) {
		if (!StringUtils.hasText(rawEmail)) {
			return;
		}
		String email = rawEmail.trim().toLowerCase(java.util.Locale.ROOT);
		User existing = userRepository.findByEmail(email);
		if (existing != null) {
			if (existing.getRole() != role) {
				existing.setRole(role);
				userRepository.save(existing);
				log.info("Gave the existing {} account its role", label);
			}
			return;
		}
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"The " + label + " password must be at least " + MIN_PASSWORD_LENGTH + " characters to create that account");
		}
		User user = new User();
		user.setEmail(email);
		user.setFirstName(firstName);
		user.setLastName("");
		user.setPassword(passwordEncoder.encode(password));
		user.setRole(role);
		user.setCreatedAt(LocalDateTime.now());
		User saved = userRepository.save(user);
		cartService.createCart(saved);
		log.info("Created {} account", label);
	}
}
