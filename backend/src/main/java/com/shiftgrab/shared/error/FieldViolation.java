package com.shiftgrab.shared.error;

/** One entry of the {@code errors} member of a {@code VALIDATION_FAILED} problem. */
public record FieldViolation(String field, String message) {

    public FieldViolation {
        field = field == null || field.isBlank() ? "request" : field;
        message = message == null ? "is invalid" : message;
    }
}
