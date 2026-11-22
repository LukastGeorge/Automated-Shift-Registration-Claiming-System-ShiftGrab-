package com.shiftgrab.audit.internal;

/** What happened, as stored in {@code audit_log.action}. Each value names the domain event it records. */
public enum AuditAction {
    SHIFT_CREATED,
    SHIFT_UPDATED,
    SHIFT_CANCELLED,
    /** Consequence of SHIFT_CANCELLED (BR-07): the staff who lost the shift. */
    SHIFT_REGISTRATIONS_CANCELLED,
    TEMPLATE_CREATED,
    SHIFTS_GENERATED,
    SHIFT_CLAIMED,
    REGISTRATION_CANCELLED,
    HOURLY_RATE_CHANGED;

    public enum Target {
        SHIFT, TEMPLATE, REGISTRATION, USER
    }
}
