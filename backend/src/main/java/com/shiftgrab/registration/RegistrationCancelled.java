package com.shiftgrab.registration;

/** A staff member cancelled their own registration before the deadline (SG-31); the slot was released. */
public record RegistrationCancelled(long registrationId, long shiftId, long userId) {
}
