package com.bazaarly.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.bazaarly.support.IntegrationTest;

/** Auth endpoints are rate limited per client IP. Real (small) limits here; other tests disable them. */
@TestPropertySource(properties = {
		"app.rate-limit.signin.requests=3", "app.rate-limit.signin.period=PT1M",
		"app.rate-limit.signup.requests=2", "app.rate-limit.signup.period=PT10M" })
class RateLimitIntegrationTest extends IntegrationTest {

	private ResultActions signinFrom(String ip, String email, String password) throws Exception {
		return mvc.perform(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("email", email, "password", password)))
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				}));
	}

	private ResultActions signupFrom(String ip) throws Exception {
		return mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("firstName", "R", "lastName", "L", "email", uniqueEmail("rl"), "password", PASSWORD)))
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				}));
	}

	@Test
	void passwordGuessingIsCutOffAfterTheLimit() throws Exception {
		String ip = "203.0.113.10";
		for (int i = 0; i < 3; i++) {
			signinFrom(ip, ADMIN_EMAIL, "guess-" + i).andExpect(status().isUnauthorized());
		}
		signinFrom(ip, ADMIN_EMAIL, "guess-4")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Too many attempts. Try again in")))
				.andExpect(jsonPath("$.details").value("uri=/auth/signin"));
		// even the right password is refused until the bucket refills
		signinFrom(ip, ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isTooManyRequests());
	}

	@Test
	void limitsArePerClient() throws Exception {
		for (int i = 0; i < 4; i++) {
			signinFrom("203.0.113.20", ADMIN_EMAIL, "wrong");
		}
		signinFrom("203.0.113.21", ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void signupHasItsOwnLimit() throws Exception {
		String ip = "203.0.113.30";
		signupFrom(ip).andExpect(status().isOk());
		signupFrom(ip).andExpect(status().isOk());
		signupFrom(ip).andExpect(status().isTooManyRequests());
		// the signin bucket is separate
		signinFrom(ip, ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isOk());
	}
}
