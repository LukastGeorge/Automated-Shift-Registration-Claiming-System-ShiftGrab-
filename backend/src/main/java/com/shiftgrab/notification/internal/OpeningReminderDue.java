package com.shiftgrab.notification.internal;

import java.util.List;

/**
 * One staff member is to be reminded of these shifts (SG-42). One event per recipient, so a failed e-mail is
 * retried for that person only and never re-sent to the others.
 */
record OpeningReminderDue(long userId, List<Long> shiftIds) {

    OpeningReminderDue {
        shiftIds = List.copyOf(shiftIds);
    }
}
