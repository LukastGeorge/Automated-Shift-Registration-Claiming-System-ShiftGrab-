package com.shiftgrab.shift.internal;

import java.time.Duration;
import java.time.Instant;

import com.shiftgrab.shared.error.ValidationErrors;

/**
 * Time rules for a shift (SG-20 AC1 plus the Sprint 2 clarifications): end after start, opening before start,
 * start in the future, at most {@link #MAX_DURATION} long. Shared by create, edit and template generation.
 */
public final class ShiftRules {

    public static final Duration MAX_DURATION = Duration.ofHours(12);

    private ShiftRules() {
    }

    public static ValidationErrors checkTimes(Instant startAt, Instant endAt, Instant registrationOpenAt, Instant now) {
        ValidationErrors errors = new ValidationErrors();
        errors.rejectIf(!startAt.isAfter(now), "startAt", "must be in the future");
        errors.rejectIf(!endAt.isAfter(startAt), "endAt", "must be after startAt");
        errors.rejectIf(endAt.isAfter(startAt) && Duration.between(startAt, endAt).compareTo(MAX_DURATION) > 0,
                "endAt", "shift must not be longer than 12 hours");
        errors.rejectIf(!registrationOpenAt.isBefore(startAt), "registrationOpenAt", "must be before startAt");
        return errors;
    }
}
