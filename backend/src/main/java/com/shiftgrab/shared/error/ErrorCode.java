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

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
