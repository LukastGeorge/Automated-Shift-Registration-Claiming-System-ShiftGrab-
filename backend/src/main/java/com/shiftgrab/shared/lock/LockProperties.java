package com.shiftgrab.shared.lock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shiftgrab.lock.*} (ADR-0004).
 *
 * @param provider     {@code redis} (default, multi-instance) or {@code in-memory} (single JVM, local experiments)
 * @param redisAddress Redis URI, e.g. {@code redis://localhost:6379}
 * @param waitTime     how long a request may wait for all its locks before CLAIM_BUSY
 * @param leaseTime    automatic release if the holder dies; must exceed the longest claim transaction
 */
@ConfigurationProperties("shiftgrab.lock")
public record LockProperties(String provider, String redisAddress, Duration waitTime, Duration leaseTime) {

    public LockProperties {
        provider = provider == null ? "redis" : provider;
        redisAddress = redisAddress == null ? "redis://localhost:6379" : redisAddress;
        waitTime = waitTime == null ? Duration.ofSeconds(3) : waitTime;
        leaseTime = leaseTime == null ? Duration.ofSeconds(10) : leaseTime;
    }
}
