package com.shiftgrab.shared.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/** Chooses the lock strategy from {@code shiftgrab.lock.provider} (ADR-0004). Redis is the default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LockProperties.class)
public class LockConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "shiftgrab.lock.provider", havingValue = "redis", matchIfMissing = true)
    static class Redis {

        /**
         * Lazy connection on purpose: the application starts even if Redis is briefly down; claims then fail
         * closed with LOCK_UNAVAILABLE until it is back (Sprint 3 decision), while every other API keeps working.
         */
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient(LockProperties properties) {
            Config config = new Config();
            config.setLazyInitialization(true);
            config.useSingleServer()
                    .setAddress(properties.redisAddress())
                    .setConnectTimeout(2_000)
                    .setTimeout(2_000)
                    .setRetryAttempts(1);
            return Redisson.create(config);
        }

        @Bean
        ShiftLockProvider redisLockProvider(RedissonClient redisson, LockProperties properties, MeterRegistry meters) {
            return new RedisLockProvider(redisson, properties, meters);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "shiftgrab.lock.provider", havingValue = "in-memory")
    ShiftLockProvider inMemoryLockProvider(LockProperties properties) {
        return new InMemoryShiftLockProvider(properties);
    }
}
