package com.shiftgrab.shared.error;

import java.sql.SQLTransientConnectionException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Single place that turns exceptions into RFC 7807 {@code application/problem+json} responses with a
 * {@code code} member (SRS §6). Framework exceptions are handled by the {@link ResponseEntityExceptionHandler}
 * base class and only enriched with a code here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage(), request.getRequestURI());
        ex.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.code().status());
        if (ex.code().retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.code().retryAfterSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return validationProblem(violations, request.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        ErrorCode code = ConstraintViolationTranslator.translate(ex).orElse(ErrorCode.DATA_CONFLICT);
        if (code == ErrorCode.DATA_CONFLICT) {
            // Unknown constraints mean a validation gap in the service layer: worth a warning, not a 500.
            log.warn("Unmapped data integrity violation (constraint={})",
                    ConstraintViolationTranslator.constraintName(ex).orElse("unknown"), ex);
        }
        return Problems.of(code, code.title(), request.getRequestURI());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return Problems.of(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by someone else. Reload and try again.", request.getRequestURI());
    }

    /** Raised by method security ({@code @PreAuthorize}) inside the MVC layer. */
    /**
     * SG-81: the pool had no free connection within {@code connectionTimeout} (1 s). Temporary by nature, so 503
     * with Retry-After instead of a 500 that looks like a bug and is not retried. Spring wraps Hikari's timeout
     * differently depending on where it happens (opening a transaction or a plain query), so the root cause decides;
     * anything else is a real failure and stays a 500.
     */
    @ExceptionHandler({ CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class })
    ResponseEntity<ProblemDetail> handleNoConnection(Exception ex, HttpServletRequest request) {
        if (!causedByPoolTimeout(ex)) {
            return ResponseEntity.internalServerError().body(handleUnexpected(ex, request));
        }
        log.warn("No database connection available for {} {}: {}", forLog(request.getMethod()),
                forLog(request.getRequestURI()), ex.getMessage());
        return handleBusiness(new BusinessException(ErrorCode.SERVICE_BUSY,
                "The service is busy right now. Please retry in a moment."), request);
    }

    static boolean causedByPoolTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return Problems.of(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.",
                request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return Problems.of(ErrorCode.UNAUTHORIZED, "Authentication is required.", request.getRequestURI());
    }

    /** Last resort: log the cause, never leak it to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", forLog(request.getMethod()), forLog(request.getRequestURI()), ex);
        return Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request.getRequestURI());
    }

    /**
     * Request values are attacker-controlled: a URL containing an encoded CR/LF would otherwise start a forged log
     * line (log injection, OWASP A09; flagged by CodeQL on SG-81). Line breaks become a visible {@code _}.
     */
    static String forLog(String value) {
        return value == null ? null : value.replaceAll("[\r\n]", "_");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(violations, path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(fieldName(error, result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(violations, path(request)));
    }

    /**
     * Adds a {@code code} to problems produced by the base class (malformed JSON, 404, 405, ...). This is the
     * last hook before the response is written, after the base class has built the body from the exception.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(Problems.CODE))) {
            problem.setProperty(Problems.CODE, codeFor(statusCode).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> status.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static ProblemDetail validationProblem(List<FieldViolation> violations, String path) {
        BusinessException ex = ValidationErrors.exception(violations);
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage(), path);
        ex.properties().forEach(problem::setProperty);
        return problem;
    }

    private static String fieldName(MessageSourceResolvable error, String parameterName) {
        return error instanceof FieldError fieldError ? fieldError.getField() : parameterName;
    }

    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
