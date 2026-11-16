package com.shiftgrab.shift;

/** Published after a manager edits a shift (SG-21); consumed by audit (SG-52) and realtime (SG-40). */
public record ShiftChanged(long shiftId, long actorId, boolean timesChanged) {
}
