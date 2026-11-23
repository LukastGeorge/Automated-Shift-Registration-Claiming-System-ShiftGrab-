package com.shiftgrab.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * SG-81, found by k6 run 10: Hikari's connection timeout reached the client as a 500 when it happened while opening
 * a transaction. Both wrappings must give 503 SERVICE_BUSY with Retry-After; a genuine connection failure stays 500.
 */
class PoolExhaustionHandlingTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shifts/1/registrations");

    private static final SQLTransientConnectionException POOL_TIMEOUT =
            new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out");

    @Test
    void poolTimeoutWhileOpeningTransactionIsServiceBusy() {
        ResponseEntity<ProblemDetail> response = handler.handleNoConnection(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new RuntimeException(POOL_TIMEOUT)), request);

        assertServiceBusy(response);
    }

    @Test
    void poolTimeoutOnPlainQueryIsServiceBusy() {
        assertServiceBusy(handler.handleNoConnection(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", POOL_TIMEOUT), request));
    }

    @Test
    void realConnectionFailureStaysInternalError() {
        ResponseEntity<ProblemDetail> response = handler.handleNoConnection(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                        new SQLException("password authentication failed", "28P01")), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    private static void assertServiceBusy(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "SERVICE_BUSY");
    }
}
