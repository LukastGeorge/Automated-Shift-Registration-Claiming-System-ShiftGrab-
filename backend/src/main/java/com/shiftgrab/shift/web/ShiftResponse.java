package com.shiftgrab.shift.web;

import java.time.Instant;

import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftStatus;

/** API representation of a shift. Instants are serialised in UTC (ISO-8601, {@code Z}). */
record ShiftResponse(
        long id,
        String name,
        Instant startAt,
        Instant endAt,
        Instant registrationOpenAt,
        int maxSlots,
        int bookedSlots,
        int availableSlots,
        ShiftStatus status,
        Long templateId,
        long version) {

    static ShiftResponse from(Shift shift) {
        return new ShiftResponse(shift.id(), shift.name(), shift.startAt(), shift.endAt(),
                shift.registrationOpenAt(), shift.maxSlots(), shift.bookedSlots(), shift.availableSlots(),
                shift.status(), shift.templateId(), shift.version());
    }
}
