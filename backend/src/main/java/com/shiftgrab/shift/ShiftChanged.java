package com.shiftgrab.shift;

/**
 * Published after a manager edits a shift (SG-21); consumed by audit (SG-52) and realtime (SG-40).
 * {@code before} and {@code after} let consumers describe the change without reading the database again.
 */
public record ShiftChanged(long shiftId, long actorId, boolean timesChanged, Shift before, Shift after) {
}
