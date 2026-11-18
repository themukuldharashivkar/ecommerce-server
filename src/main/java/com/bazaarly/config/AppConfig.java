package com.bazaarly.config;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.bazaarly.exception.ErrorDetails;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless JWT security.
 * Public: catalog reads, product reviews/ratings, auth, health and API docs. Admin API: ROLE_ADMIN.
 * Everything else under /api requires a valid token. No HTTP Basic, no form login, no sessions.
 */
@Configuration
public class AppConfig {

	private final JwtTokenProvider jwtTokenProvider;
	private final List<String> allowedOrigins;
	private final ObjectMapper objectMapper;
	private final AuthRateLimitFilter authRateLimitFilter;

	public AppConfig(JwtTokenProvider jwtTokenProvider, @Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
			ObjectMapper objectMapper, AuthRateLimitFilter authRateLimitFilter) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.allowedOrigins = allowedOrigins;
		this.objectMapper = objectMapper;
		this.authRateLimitFilter = authRateLimitFilter;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable()) // stateless API with bearer tokens, no cookies
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/reviews/product/**", "/api/ratings/product/**").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
						// the demo "viewer" admin may read the admin API; every change needs ROLE_ADMIN
						.requestMatchers(HttpMethod.GET, "/api/admin/**").hasAnyRole("ADMIN", "ADMIN_VIEWER")
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/**", "/actuator/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((req, res, e) ->
								writeError(req, res, HttpStatus.UNAUTHORIZED, "Authentication required: send a valid Bearer token"))
						.accessDeniedHandler((req, res, e) ->
								writeError(req, res, HttpStatus.FORBIDDEN, "You do not have permission to do this")))
				.addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(new JwtTokenValidator(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/** 401/403 bodies use the same shape as every other error. */
	private void writeError(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String message)
			throws java.io.IOException {
		res.setStatus(status.value());
		res.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(res.getOutputStream(), new ErrorDetails(message, "uri=" + req.getRequestURI(), LocalDateTime.now()));
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOrigins(allowedOrigins); // only the storefront origin(s) from CORS_ALLOWED_ORIGINS
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		cfg.setExposedHeaders(List.of("Authorization", "X-Total-Count", "Retry-After"));
		cfg.setAllowCredentials(true);
		cfg.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cfg);
		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
