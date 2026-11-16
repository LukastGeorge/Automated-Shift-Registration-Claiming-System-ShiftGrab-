package com.shiftgrab.shift;

/** Published after a manager creates a shift; consumed by audit (SG-52) and realtime (SG-40). */
public record ShiftCreated(long shiftId, long actorId) {
}
