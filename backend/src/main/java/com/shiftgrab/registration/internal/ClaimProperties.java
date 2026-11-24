package com.shiftgrab.registration.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shiftgrab.claims.*}: how the claim path behaves under contention (SG-81, ADR-0004).
 *
 * @param lockTimeout      longest wait for the shift row inside PostgreSQL before CLAIM_BUSY
 * @param statementTimeout longest single statement of the claim transaction before CLAIM_BUSY
 * @param soldOutMemo      how long an instance trusts "this shift is full" without asking the database; 0 = off
 */
@ConfigurationProperties("shiftgrab.claims")
public record ClaimProperties(Duration lockTimeout, Duration statementTimeout, Duration soldOutMemo) {

    public ClaimProperties {
        lockTimeout = lockTimeout == null ? Duration.ofSeconds(1) : lockTimeout;
        statementTimeout = statementTimeout == null ? Duration.ofSeconds(2) : statementTimeout;
        soldOutMemo = soldOutMemo == null ? Duration.ofSeconds(1) : soldOutMemo;
    }
}
