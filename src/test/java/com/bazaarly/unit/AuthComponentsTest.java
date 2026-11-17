package com.bazaarly.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bazaarly.config.AdminBootstrap;
import com.bazaarly.config.JwtTokenProvider;
import com.bazaarly.exception.UserException;
import com.bazaarly.modal.User;
import com.bazaarly.repository.UserRepository;
import com.bazaarly.service.CartService;
import com.bazaarly.service.CustomUserDetails;
import com.bazaarly.service.UserServiceImplementation;
import com.bazaarly.user.domain.UserRole;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;

/** JWT handling, role mapping, user lookup and admin bootstrap. */
class AuthComponentsTest {

	static final String SECRET = "unit-test-secret-0123456789abcdef0123456789";

	static UsernamePasswordAuthenticationToken auth(String email, String role) {
		return new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority(role)));
	}

	@Nested
	class JwtTokens {

		JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000);

		@Test
		void tokenRoundTripsEmailAndRole() {
			String token = provider.generateToken(auth("a@b.c", "ROLE_ADMIN"));

			assertThat(provider.getEmailFromJwtToken("Bearer " + token)).isEqualTo("a@b.c");
			assertThat(provider.parseClaims(token).get("authorities")).isEqualTo("ROLE_ADMIN");
		}

		@Test
		void secretShorterThan256BitsIsRejected() {
			assertThatThrownBy(() -> new JwtTokenProvider("too-short", 60_000))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("32 bytes");
		}

		@Test
		void tokenSignedWithAnotherKeyIsRejected() {
			String foreign = new JwtTokenProvider("another-secret-0123456789abcdef0123456789", 60_000)
					.generateToken(auth("a@b.c", "ROLE_ADMIN"));

			assertThatThrownBy(() -> provider.parseClaims(foreign)).isInstanceOf(SignatureException.class);
		}

		@Test
		void tamperedTokenIsRejected() {
			String token = provider.generateToken(auth("a@b.c", "ROLE_USER"));
			String[] parts = token.split("\\.");
			String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
					.encodeToString("{\"email\":\"a@b.c\",\"authorities\":\"ROLE_ADMIN\"}".getBytes());

			assertThatThrownBy(() -> provider.parseClaims(parts[0] + "." + forgedPayload + "." + parts[2]))
					.isInstanceOf(SignatureException.class);
		}

		@Test
		void expiredTokenIsRejected() {
			String expired = new JwtTokenProvider(SECRET, -1_000).generateToken(auth("a@b.c", "ROLE_USER"));

			assertThatThrownBy(() -> provider.parseClaims(expired)).isInstanceOf(ExpiredJwtException.class);
		}
	}

	@Nested
	@ExtendWith(MockitoExtension.class)
	class UserDetailsAndLookup {

		@Mock UserRepository userRepository;

		User user(UserRole role) {
			User user = new User();
			user.setId(1L);
			user.setEmail("a@b.c");
			user.setPassword("hash");
			user.setRole(role);
			return user;
		}

		@Test
		void roleBecomesTheOnlyAuthority() {
			when(userRepository.findByEmail("a@b.c")).thenReturn(user(UserRole.ROLE_ADMIN));

			UserDetails details = new CustomUserDetails(userRepository).loadUserByUsername("a@b.c");

			assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
		}

		@Test
		void userWithoutARoleIsANormalUser() {
			when(userRepository.findByEmail("a@b.c")).thenReturn(user(null));

			UserDetails details = new CustomUserDetails(userRepository).loadUserByUsername("a@b.c");

			assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
		}

		@Test
		void unknownEmailIsNotFound() {
			assertThatThrownBy(() -> new CustomUserDetails(userRepository).loadUserByUsername("x@y.z"))
					.isInstanceOf(UsernameNotFoundException.class);
		}

		@Test
		void profileIsLoadedFromTheTokensEmail() throws Exception {
			JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000);
			when(userRepository.findByEmail("a@b.c")).thenReturn(user(UserRole.ROLE_USER));
			UserServiceImplementation service = new UserServiceImplementation(userRepository, provider);

			User found = service.findUserProfileByJwt("Bearer " + provider.generateToken(auth("a@b.c", "ROLE_USER")));

			assertThat(found.getEmail()).isEqualTo("a@b.c");
		}

		@Test
		void tokenForADeletedUserIsAnError() {
			JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000);
			UserServiceImplementation service = new UserServiceImplementation(userRepository, provider);

			assertThatThrownBy(() -> service.findUserProfileByJwt("Bearer " + provider.generateToken(auth("gone@b.c", "ROLE_USER"))))
					.isInstanceOf(UserException.class);
		}

		@Test
		void findUserByIdOfAnUnknownUserFails() {
			when(userRepository.findById(5L)).thenReturn(Optional.empty());
			UserServiceImplementation service = new UserServiceImplementation(userRepository, null);

			assertThatThrownBy(() -> service.findUserById(5L)).isInstanceOf(UserException.class);
		}
	}

	@Nested
	@ExtendWith(MockitoExtension.class)
	class AdminAccountBootstrap {

		@Mock UserRepository userRepository;
		@Mock CartService cartService;
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		AdminBootstrap bootstrap(String email, String password) {
			return new AdminBootstrap(userRepository, cartService, encoder, email, password);
		}

		@Test
		void createsTheAdminWithAHashedPasswordAndACart() {
			when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			bootstrap("admin@shop.test", "long-enough-password").run();

			ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
			verify(userRepository).save(saved.capture());
			assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ROLE_ADMIN);
			assertThat(saved.getValue().getPassword()).isNotEqualTo("long-enough-password");
			assertThat(encoder.matches("long-enough-password", saved.getValue().getPassword())).isTrue();
			verify(cartService).createCart(saved.getValue());
		}

		@Test
		void promotesAnExistingUserAndKeepsTheirPassword() {
			User existing = new User();
			existing.setEmail("admin@shop.test");
			existing.setPassword("their-hash");
			existing.setRole(UserRole.ROLE_USER);
			when(userRepository.findByEmail("admin@shop.test")).thenReturn(existing);

			bootstrap("admin@shop.test", "ignored-for-existing").run();

			assertThat(existing.getRole()).isEqualTo(UserRole.ROLE_ADMIN);
			assertThat(existing.getPassword()).isEqualTo("their-hash");
			verify(userRepository).save(existing);
			verifyNoInteractions(cartService);
		}

		@Test
		void doesNothingWhenNoAdminIsConfigured() {
			bootstrap("", "").run();

			verifyNoInteractions(userRepository, cartService);
		}

		@Test
		void refusesAShortPassword() {
			assertThatThrownBy(() -> bootstrap("admin@shop.test", "short").run())
					.isInstanceOf(IllegalStateException.class);
			verify(userRepository, never()).save(any());
		}
	}
}
