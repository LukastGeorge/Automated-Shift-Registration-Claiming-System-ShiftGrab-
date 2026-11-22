package com.shiftgrab.registration.internal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a time range into the ISO weeks (Monday 00:00 to Monday 00:00 in the organisation zone) it overlaps.
 * BR-04 decision: a shift crossing a week boundary counts its hours in each week it actually covers.
 */
final class IsoWeeks {

    private IsoWeeks() {
    }

    /** The week [start, end) containing {@code instant}. */
    static Week weekOf(Instant instant, ZoneId zone) {
        ZonedDateTime monday = instant.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone);
        return new Week(monday.toInstant(), monday.plusWeeks(1).toInstant());
    }

    /** Each week overlapped by [start, end), with the length of the overlap. */
    static List<Segment> split(Instant start, Instant end, ZoneId zone) {
        List<Segment> segments = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Week week = weekOf(cursor, zone);
            Instant segmentEnd = end.isBefore(week.end()) ? end : week.end();
            segments.add(new Segment(week, Duration.between(cursor, segmentEnd)));
            cursor = segmentEnd;
        }
        return segments;
    }

    record Week(Instant start, Instant end) {
    }

    record Segment(Week week, Duration duration) {
    }
}
