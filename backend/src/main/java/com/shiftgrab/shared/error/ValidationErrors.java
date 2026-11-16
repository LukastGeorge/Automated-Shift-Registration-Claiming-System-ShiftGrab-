package com.shiftgrab.shared.error;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Collects cross-field rule violations that Bean Validation cannot express (e.g. {@code endAt > startAt}) and
 * reports them in the same {@code VALIDATION_FAILED} shape as annotation-based errors.
 */
public final class ValidationErrors {

    static final Comparator<FieldViolation> ORDER =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

    private final List<FieldViolation> violations = new ArrayList<>();

    public ValidationErrors rejectIf(boolean condition, String field, String message) {
        if (condition) {
            violations.add(new FieldViolation(field, message));
        }
        return this;
    }

    public boolean hasErrors() {
        return !violations.isEmpty();
    }

    public void throwIfAny() {
        if (hasErrors()) {
            throw exception(violations);
        }
    }

    static BusinessException exception(List<FieldViolation> violations) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.",
                Map.of("errors", violations.stream().sorted(ORDER).toList()));
    }
}
