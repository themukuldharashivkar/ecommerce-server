package com.bazaarly.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterRegistrationConfig {

	/** The rate limiter runs inside the Spring Security chain only; stop Boot registering it a second time. */
	@Bean
	FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilterRegistration(AuthRateLimitFilter filter) {
		FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
