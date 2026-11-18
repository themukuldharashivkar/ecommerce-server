package com.bazaarly.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	/** Injected where time matters, so tests can control it. */
	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}
}
