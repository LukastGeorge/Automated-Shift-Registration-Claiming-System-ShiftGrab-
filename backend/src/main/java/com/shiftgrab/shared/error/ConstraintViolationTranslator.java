package com.shiftgrab.shared.error;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps database constraint names to domain error codes (ADR-0005: the database is the last line of defence,
 * and its rejections must still reach the client as meaningful codes, not as a generic 500/409).
 *
 * <p>The constraint name is read from the {@link SQLException} message, which PostgreSQL formats as
 * {@code ... violates <kind> constraint "<name>"}. This keeps the shared module free of a compile-time
 * dependency on the JDBC driver.
 */
final class ConstraintViolationTranslator {

    private static final Pattern CONSTRAINT_NAME = Pattern.compile("constraint \"([^\"]+)\"");

    private static final Map<String, ErrorCode> CODES = Map.of(
            "ck_shifts_slots", ErrorCode.SHIFT_FULL,
            "uq_registrations_active", ErrorCode.ALREADY_REGISTERED,
            "ex_registrations_no_overlap", ErrorCode.SHIFT_OVERLAP);

    private ConstraintViolationTranslator() {
    }

    static Optional<ErrorCode> translate(Throwable exception) {
        return constraintName(exception).map(CODES::get);
    }

    static Optional<String> constraintName(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof SQLException && t.getMessage() != null) {
                Matcher matcher = CONSTRAINT_NAME.matcher(t.getMessage());
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }
}
