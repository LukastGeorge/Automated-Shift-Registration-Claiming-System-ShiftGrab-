package com.shiftgrab.identity;

import java.math.BigDecimal;

/** Read model of a ShiftGrab user, exposed to other modules. */
public record UserAccount(
        long id,
        String keycloakSub,
        String fullName,
        String email,
        Role role,
        BigDecimal hourlyRate,
        boolean reminderOptIn) {

    public boolean isManager() {
        return role == Role.MANAGER;
    }
}
