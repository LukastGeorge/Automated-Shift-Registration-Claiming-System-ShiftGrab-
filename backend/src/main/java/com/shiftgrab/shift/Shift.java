package com.shiftgrab.shift;

import java.time.Duration;
import java.time.Instant;

/** Read model of a shift, exposed to other modules (e.g. registration in Sprint 3). */
public record Shift(
        long id,
        String name,
        Instant startAt,
        Instant endAt,
        Instant registrationOpenAt,
        int maxSlots,
        int bookedSlots,
        ShiftStatus status,
        Long templateId,
        long version) {

    public int availableSlots() {
        return status == ShiftStatus.CANCELLED ? 0 : maxSlots - bookedSlots;
    }

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startAt);
    }

    /** Staff only see shifts they could still act on (SG-24). */
    public boolean isVisibleToStaff(Instant now) {
        return status != ShiftStatus.CANCELLED && !hasStarted(now);
    }
}
