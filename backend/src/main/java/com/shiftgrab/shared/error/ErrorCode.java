package com.shiftgrab.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the {@code code} field of every problem response (SRS §6).
 * Clients branch on the code, never on the human-readable {@code detail}.
 */
public enum ErrorCode {

    // Business codes from SRS §6
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    SHIFT_NOT_FOUND(HttpStatus.NOT_FOUND, "Shift not found"),
    SHIFT_FULL(HttpStatus.CONFLICT, "Shift is full"),
    REGISTRATION_NOT_OPEN(HttpStatus.CONFLICT, "Registration is not open yet"),
    SHIFT_OVERLAP(HttpStatus.CONFLICT, "Shift overlaps another confirmed shift"),
    WEEKLY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Weekly hour limit exceeded"),
    ALREADY_REGISTERED(HttpStatus.CONFLICT, "Already registered for this shift"),
    CANCEL_DEADLINE_PASSED(HttpStatus.CONFLICT, "Cancellation deadline has passed"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Resource was modified concurrently"),
    SHIFT_HAS_REGISTRATIONS(HttpStatus.CONFLICT, "Shift already has registrations"),

    // Shift management codes added in Sprint 2 (SRS §6)
    SHIFT_CANCELLED(HttpStatus.CONFLICT, "Shift is cancelled"),
    SHIFT_ALREADY_STARTED(HttpStatus.CONFLICT, "Shift has already started"),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "Shift template not found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "If-Match header required"),

    REGISTRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Registration not found"),
    // SG-81: a manager changed the shift while it was being claimed; review it before trying again
    SHIFT_CHANGED(HttpStatus.CONFLICT, "Shift was changed while claiming"),

    // Claiming under load (Sprint 3, ADR-0004): both are temporary, so they carry Retry-After
    CLAIM_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Too many concurrent requests", 1),
    LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Claiming is temporarily unavailable", 5),
    // SG-81: no database connection within Hikari's short connectionTimeout; fail fast instead of queueing
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Service is busy", 1),

    // Generic codes so that every error, including framework ones, carries a code
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "Data conflict"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;
    private final int retryAfterSeconds;

    ErrorCode(HttpStatus status, String title) {
        this(status, title, 0);
    }

    ErrorCode(HttpStatus status, String title, int retryAfterSeconds) {
        this.status = status;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds for the {@code Retry-After} header, or 0 when the error is not worth retrying as is. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
