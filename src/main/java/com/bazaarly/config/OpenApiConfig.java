package com.bazaarly.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * OpenAPI document at /v3/api-docs, Swagger UI at /swagger-ui.html.
 * To call protected endpoints from Swagger UI: POST /auth/signin, copy the "jwt" value,
 * click Authorize and paste it (without the "Bearer " prefix).
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "Bazaarly API", version = "1.0",
		description = "Clothing store API: catalog, cart, orders, Razorpay (test mode) payments, reviews and admin. "
				+ "Errors share one body: {error, details, timestamp, fieldErrors?}. "
				+ "List endpoints return a JSON array and the total in the X-Total-Count header."))
@SecurityScheme(name = OpenApiConfig.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
		description = "JWT from POST /auth/signin or /auth/signup")
public class OpenApiConfig {

	public static final String BEARER = "bearerAuth";
}
