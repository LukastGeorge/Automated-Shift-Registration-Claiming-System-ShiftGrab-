package com.shiftgrab.shift;

/**
 * Published inside the cancelling transaction (SG-22). The registration module listens synchronously and
 * cancels the shift's registrations in the same transaction (BR-07), so either everything is cancelled or
 * nothing is. {@code reason} is optional and is passed on to the staff notification (SG-41).
 */
public record ShiftCancelled(long shiftId, long actorId, String reason) {
}
