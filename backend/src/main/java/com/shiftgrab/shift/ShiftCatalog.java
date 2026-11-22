package com.shiftgrab.shift;

import java.time.Instant;
import java.util.List;

/** Read-only shift queries for other modules. */
public interface ShiftCatalog {

    /** OPEN shifts whose registration opens in {@code (after, until]}, soonest first (SG-42 reminders). */
    List<Shift> findOpeningBetween(Instant after, Instant until);
}
