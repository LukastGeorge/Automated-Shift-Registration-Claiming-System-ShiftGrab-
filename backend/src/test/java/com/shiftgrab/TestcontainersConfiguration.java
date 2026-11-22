package com.shiftgrab;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests (ADR-0002: same database engine in every environment; ADR-0004: same
 * lock service). Containers are singletons per test JVM thanks to Spring's context caching.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16.16-alpine");
    }

    @Bean
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);
    }

    /** Redisson is configured from {@code shiftgrab.lock.redis-address}, not from Spring Data Redis. */
    @Bean
    DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
        return registry -> registry.add("shiftgrab.lock.redis-address",
                () -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
    }
}
