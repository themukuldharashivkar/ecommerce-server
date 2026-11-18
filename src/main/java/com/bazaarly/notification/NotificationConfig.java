package com.bazaarly.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class NotificationConfig {

	public static final String EXECUTOR = "notificationExecutor";
	private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

	/**
	 * Bounded pool for background notifications. When the queue is full the task is dropped with a
	 * warning instead of blocking the payment request; the outbox row stays PENDING and the sweeper sends it.
	 */
	@Bean(EXECUTOR)
	ThreadPoolTaskExecutor notificationExecutor(@Value("${app.notifications.pool-size}") int poolSize,
			@Value("${app.notifications.queue-capacity}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("notify-");
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setRejectedExecutionHandler((task, pool) ->
				log.warn("Notification queue full; the confirmation stays PENDING for the retry sweeper"));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}
}
