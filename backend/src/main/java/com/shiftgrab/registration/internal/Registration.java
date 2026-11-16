package com.shiftgrab.registration.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** A staff member's hold on one shift slot; the time range is a copy of the shift's (design §5). */
public record Registration(
        long id,
        long shiftId,
        long userId,
        RegistrationStatus status,
        Instant startAt,
        Instant endAt,
        BigDecimal hourlyRateSnapshot,
        Instant registeredAt,
        Instant cancelledAt) {

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }
}
