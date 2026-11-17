package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.bazaarly.config.JwtTokenProvider;
import com.bazaarly.support.IntegrationTest;

import io.jsonwebtoken.Claims;

class AuthIntegrationTest extends IntegrationTest {

	@Value("${app.jwt.secret}")
	String jwtSecret;

	@Test
	void signupReturnsAUserTokenThatOpensProtectedEndpoints() throws Exception {
		String email = uniqueEmail("signup");
		String token = signUp(email);

		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("ROLE_USER"))
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void tokenCarriesTheUsersRole() throws Exception {
		String token = signUp(uniqueEmail("role"));
		Claims claims = new JwtTokenProvider(jwtSecret, 60_000).parseClaims(token);
		assertThat(claims.get("authorities")).isEqualTo("ROLE_USER");
	}

	@Test
	void signinWithCorrectPasswordReturnsToken() throws Exception {
		String email = uniqueEmail("signin");
		signUp(email);
		String token = signIn(email, PASSWORD);

		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isAccepted());
	}

	@Test
	void signinWithWrongPasswordIs401() throws Exception {
		String email = uniqueEmail("wrongpw");
		signUp(email);

		mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("email", email, "password", "not-the-password"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Invalid username or password"));
	}

	@Test
	void signinWithUnknownEmailLooksTheSameAsAWrongPassword() throws Exception {
		mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("email", uniqueEmail("nobody"), "password", PASSWORD))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Invalid username or password"));
	}

	@Test
	void signupWithAnEmailAlreadyInUseIs400() throws Exception {
		String email = uniqueEmail("dup");
		signUp(email);

		mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("firstName", "A", "lastName", "B", "email", email, "password", PASSWORD))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Email Is Already Used With Another Account"));
	}

	@Test
	void protectedEndpointWithoutTokenIs401() throws Exception {
		mvc.perform(get("/api/users/profile")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/cart/")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/orders/user")).andExpect(status().isUnauthorized());
	}

	@Test
	void malformedTokenIs401() throws Exception {
		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSignedWithAnotherSecretIs401() throws Exception {
		JwtTokenProvider otherKey = new JwtTokenProvider("a-completely-different-secret-0123456789abcdef", 60_000);
		String forged = otherKey.generateToken(new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/orders/").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenIs401() throws Exception {
		String email = uniqueEmail("expired");
		signUp(email);
		JwtTokenProvider alreadyExpired = new JwtTokenProvider(jwtSecret, -60_000);
		String expired = alreadyExpired.generateToken(new UsernamePasswordAuthenticationToken(email, null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))));

		mvc.perform(get("/api/users/profile").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void catalogIsPublic() throws Exception {
		mvc.perform(get("/api/products/search").param("q", "anything")).andExpect(status().isOk());
	}
}
