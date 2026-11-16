package com.shiftgrab.shift.internal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

/**
 * Recurring shift definition (SG-23). Times are wall-clock times in the organisation zone; an end time not after
 * the start time means the shift ends the next day (overnight shift).
 */
public record ShiftTemplate(
        long id,
        String name,
        SortedSet<DayOfWeek> weekdays,
        LocalTime startTime,
        LocalTime endTime,
        int maxSlots,
        Duration openBefore,
        LocalDate validFrom,
        LocalDate validTo) {

    public static Duration duration(LocalTime start, LocalTime end) {
        Duration d = Duration.between(start, end);
        return d.isNegative() || d.isZero() ? d.plusDays(1) : d;
    }

    public boolean isOvernight() {
        return !endTime.isAfter(startTime);
    }

    /** Every occurrence in [validFrom, validTo], converted to instants in {@code zone}. */
    public List<Occurrence> occurrences(ZoneId zone) {
        List<Occurrence> result = new ArrayList<>();
        for (LocalDate day = validFrom; !day.isAfter(validTo); day = day.plusDays(1)) {
            if (weekdays.contains(day.getDayOfWeek())) {
                ZonedDateTime start = ZonedDateTime.of(day, startTime, zone);
                ZonedDateTime end = ZonedDateTime.of(isOvernight() ? day.plusDays(1) : day, endTime, zone);
                result.add(new Occurrence(start.toInstant(), end.toInstant(), start.toInstant().minus(openBefore)));
            }
        }
        return result;
    }

    public record Occurrence(Instant startAt, Instant endAt, Instant registrationOpenAt) {
    }
}
