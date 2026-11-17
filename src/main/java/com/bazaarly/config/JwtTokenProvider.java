package com.bazaarly.config;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtTokenProvider {

	private static final int MIN_SECRET_BYTES = 32; // 256 bits, required for HMAC-SHA256

	private final SecretKey key;
	private final long expirationMs;

	public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.expiration-ms}") long expirationMs) {
		byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (secretBytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long");
		}
		this.key = Keys.hmacShaKeyFor(secretBytes);
		this.expirationMs = expirationMs;
	}

	public String generateToken(Authentication auth) {

		String jwt=Jwts.builder()
				.setIssuedAt(new Date())
				.setExpiration(new Date(new Date().getTime()+expirationMs))
				.claim("email",auth.getName())
				.signWith(key)
				.compact();

		return jwt;
	}

	public String getEmailFromJwtToken(String jwt) {
		jwt=jwt.substring(7);

		Claims claims=parseClaims(jwt);
		String email=String.valueOf(claims.get("email"));

		return email;
	}

	/** Verifies the signature and expiry of a raw token (no "Bearer " prefix). */
	public Claims parseClaims(String token) {
		return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
	}

	public String populateAuthorities(Collection<? extends GrantedAuthority> collection) {
		Set<String> auths=new HashSet<>();

		for(GrantedAuthority authority:collection) {
			auths.add(authority.getAuthority());
		}
		return String.join(",",auths);
	}

}
