package com.shiftgrab.registration;

/** A staff member claimed a slot (SG-30). Consumed after commit by notification, realtime and audit. */
public record ShiftClaimed(long registrationId, long shiftId, long userId) {
}
