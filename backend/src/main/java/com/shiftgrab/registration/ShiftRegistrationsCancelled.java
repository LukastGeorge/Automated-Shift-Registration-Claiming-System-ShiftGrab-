package com.shiftgrab.registration;

import java.util.List;

/**
 * Registrations of a shift were cancelled because the manager {@code actorId} cancelled the shift (BR-07). Carries
 * the affected staff so the notification module can e-mail them after commit (SG-41).
 */
public record ShiftRegistrationsCancelled(long shiftId, long actorId, List<Long> userIds, String reason) {

    public ShiftRegistrationsCancelled {
        userIds = List.copyOf(userIds);
    }
}
