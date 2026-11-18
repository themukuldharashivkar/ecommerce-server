package com.bazaarly.config;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bazaarly.exception.ErrorDetails;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-client-IP token buckets (Bucket4j) on POST /auth/signin and /auth/signup, to slow down
 * password guessing and bulk account creation. Over the limit: 429 with Retry-After.
 *
 * Buckets live in this instance's memory: with several instances each enforces its own limit
 * (Bucket4j has Redis-backed buckets if that is ever needed). Behind a proxy the client IP comes from
 * X-Forwarded-For only when FORWARD_HEADERS_STRATEGY=framework (the prod default).
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
	/** Guard against memory exhaustion from requests spread over very many IPs. */
	private static final int MAX_TRACKED_CLIENTS = 100_000;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final Bandwidth signinLimit;
	private final Bandwidth signupLimit;

	public AuthRateLimitFilter(ObjectMapper objectMapper,
			@Value("${app.rate-limit.signin.requests}") long signinRequests,
			@Value("${app.rate-limit.signin.period}") Duration signinPeriod,
			@Value("${app.rate-limit.signup.requests}") long signupRequests,
			@Value("${app.rate-limit.signup.period}") Duration signupPeriod) {
		this.objectMapper = objectMapper;
		this.signinLimit = Bandwidth.builder().capacity(signinRequests).refillGreedy(signinRequests, signinPeriod).build();
		this.signupLimit = Bandwidth.builder().capacity(signupRequests).refillGreedy(signupRequests, signupPeriod).build();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod()) || limitFor(request.getRequestURI()) == null;
	}

	private Bandwidth limitFor(String uri) {
		return switch (uri) {
			case "/auth/signin" -> signinLimit;
			case "/auth/signup" -> signupLimit;
			default -> null;
		};
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		Bandwidth limit = limitFor(uri);
		if (buckets.size() > MAX_TRACKED_CLIENTS) {
			buckets.clear();
		}
		Bucket bucket = buckets.computeIfAbsent(uri + "|" + request.getRemoteAddr(),
				key -> Bucket.builder().addLimit(limit).build());

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			chain.doFilter(request, response);
			return;
		}

		long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
		log.warn("Rate limit hit on {} from {}", uri, request.getRemoteAddr());
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", String.valueOf(retryAfter));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new ErrorDetails(
				"Too many attempts. Try again in " + retryAfter + " seconds.", "uri=" + uri, LocalDateTime.now()));
	}

	/** Forget clients whose bucket has refilled completely; they would start from a full bucket anyway. */
	@Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
	public void evictIdleClients() {
		buckets.entrySet().removeIf(e -> {
			Bandwidth limit = limitFor(e.getKey().substring(0, e.getKey().indexOf('|')));
			return limit == null || e.getValue().getAvailableTokens() >= limit.getCapacity();
		});
	}
}
