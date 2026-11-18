package com.bazaarly.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Caching is on in every profile. The store is chosen with CACHE_TYPE (spring.cache.type):
 * "redis" (prod default), "simple" (in-memory, dev/test default, no Redis needed) or "none".
 * The settings below only apply when the Redis cache manager is used.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

	/**
	 * If the cache store fails (e.g. Redis is down), log it and carry on as a cache miss,
	 * so the catalog keeps working from the database instead of returning 500s.
	 */
	@Override
	public CacheErrorHandler errorHandler() {
		return new CacheErrorHandler() {
			@Override
			public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
				log.warn("Cache get failed on {} (serving from the database): {}", cache.getName(), e.getMessage());
			}

			@Override
			public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
				log.warn("Cache put failed on {}: {}", cache.getName(), e.getMessage());
			}

			@Override
			public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
				log.error("Cache evict failed on {} key {}: entry may be stale until its TTL expires: {}",
						cache.getName(), key, e.getMessage());
			}

			@Override
			public void handleCacheClearError(RuntimeException e, Cache cache) {
				log.error("Cache clear failed on {}: entries may be stale until their TTL expires: {}",
						cache.getName(), e.getMessage());
			}
		};
	}

	@Bean
	RedisCacheManagerBuilderCustomizer redisCacheCustomizer(RedisConnectionFactory connectionFactory,
			@Value("${app.cache.catalog-ttl}") Duration catalogTtl,
			@Value("${app.cache.product-ttl}") Duration productTtl) {

		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
				// values are JSON strings: store them as readable UTF-8, keys as "bazaarly:<cache>::<key>"
				.serializeValuesWith(SerializationPair.fromSerializer(RedisSerializer.string()))
				.prefixCacheNameWith("bazaarly:")
				.disableCachingNullValues();

		return builder -> builder
				// evicting a whole cache uses SCAN instead of the blocking KEYS command
				.cacheWriter(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, BatchStrategies.scan(500)))
				.withCacheConfiguration(CacheNames.CATALOG, base.entryTtl(catalogTtl))
				.withCacheConfiguration(CacheNames.PRODUCT, base.entryTtl(productTtl));
	}
}
