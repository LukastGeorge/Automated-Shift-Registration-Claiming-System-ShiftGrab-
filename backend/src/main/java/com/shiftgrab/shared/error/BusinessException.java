package com.shiftgrab.shared.error;

import java.util.Map;

/**
 * Thrown by domain services when a business rule rejects a request. Translated to an RFC 7807 response by
 * {@link GlobalExceptionHandler}; {@code properties} become extra members of the problem (e.g. {@code shiftId}).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    public BusinessException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public BusinessException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
