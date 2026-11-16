package com.shiftgrab.shared.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.TestcontainersConfiguration;
import com.shiftgrab.shared.security.KeycloakRealmRoleConverter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** SG-13: every error is {@code application/problem+json} with a stable {@code code} (SRS §6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ErrorHandlingTests.FaultyEndpoints.class })
class ErrorHandlingTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KeycloakRealmRoleConverter roleConverter;

    @Nested
    @DisplayName("Security filter chain")
    class Security {

        @Test
        void missingTokenIsUnauthorizedProblem() throws Exception {
            expectProblem(mvc.perform(get("/api/v1/me")), 401, "UNAUTHORIZED")
                    .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                    .andExpect(jsonPath("$.instance").value("/api/v1/me"));
        }

        @Test
        void pathOutsideTheApiIsForbiddenProblem() throws Exception {
            expectProblem(mvc.perform(get("/internal/anything").with(staff())), 403, "FORBIDDEN");
        }

        @Test
        void methodSecurityDenialIsForbiddenProblem() throws Exception {
            expectProblem(mvc.perform(get("/api/test/errors/manager-only").with(staff())), 403, "FORBIDDEN");
        }
    }

    @Nested
    @DisplayName("Request validation")
    class Validation {

        @Test
        void invalidBodyListsEveryFieldError() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/body").with(staff())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\" \",\"maxSlots\":0}")), 400, "VALIDATION_FAILED")
                    .andExpect(jsonPath("$.errors.length()").value(2))
                    .andExpect(jsonPath("$.errors[0].field").value("maxSlots"))
                    .andExpect(jsonPath("$.errors[1].field").value("name"));
        }

        @Test
        void invalidRequestParameterIsValidationFailed() throws Exception {
            expectProblem(mvc.perform(get("/api/test/errors/param").param("size", "0").with(staff())), 400,
                    "VALIDATION_FAILED")
                    .andExpect(jsonPath("$.errors[0].field").value("size"));
        }

        @Test
        void unreadableJsonIsMalformedRequest() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/body").with(staff())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not json")), 400, "MALFORMED_REQUEST");
        }
    }

    @Nested
    @DisplayName("Framework errors")
    class Framework {

        @Test
        void unknownApiPathIsNotFound() throws Exception {
            expectProblem(mvc.perform(get("/api/v1/does-not-exist").with(staff())), 404, "NOT_FOUND");
        }

        @Test
        void wrongMethodIsMethodNotAllowed() throws Exception {
            expectProblem(mvc.perform(delete("/api/test/errors/param").with(staff())), 405, "METHOD_NOT_ALLOWED");
        }

        @Test
        void unexpectedExceptionDoesNotLeakInternals() throws Exception {
            expectProblem(mvc.perform(get("/api/test/errors/boom").with(staff())), 500, "INTERNAL_ERROR")
                    .andExpect(jsonPath("$.detail", not(containsString("secret"))));
        }
    }

    @Nested
    @DisplayName("Domain errors")
    class Domain {

        @BeforeEach
        void seed() {
            jdbc.execute("TRUNCATE shift_registrations, shifts, shift_templates, users RESTART IDENTITY CASCADE");
            jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES "
                    + "('m', 'Manager', 'm@shiftgrab.test', 'MANAGER'), ('s', 'Staff', 's@shiftgrab.test', 'STAFF')");
            jdbc.update("""
                    INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, booked_slots, created_by)
                    VALUES ('A', '2030-01-06 08:00+07', '2030-01-06 12:00+07', '2030-01-01 08:00+07', 1, 1, 1),
                           ('B', '2030-01-06 11:00+07', '2030-01-06 15:00+07', '2030-01-01 08:00+07', 3, 0, 1)
                    """);
            jdbc.update(FaultyEndpoints.REGISTER, "A", "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");
        }

        @Test
        void businessExceptionCarriesCodeAndProperties() throws Exception {
            expectProblem(mvc.perform(get("/api/test/errors/business").with(staff())), 409, "REGISTRATION_NOT_OPEN")
                    .andExpect(jsonPath("$.title").value("Registration is not open yet"))
                    .andExpect(jsonPath("$.shiftId").value(42));
        }

        @Test
        void overbookingConstraintIsShiftFull() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/sql/overbook").with(staff())), 409, "SHIFT_FULL");
        }

        @Test
        void duplicateRegistrationConstraintIsAlreadyRegistered() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/sql/duplicate").with(staff())), 409,
                    "ALREADY_REGISTERED");
        }

        @Test
        void overlapConstraintIsShiftOverlap() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/sql/overlap").with(staff())), 409, "SHIFT_OVERLAP");
        }

        @Test
        void unmappedConstraintIsGenericDataConflict() throws Exception {
            expectProblem(mvc.perform(post("/api/test/errors/sql/duplicate-email").with(staff())), 409,
                    "DATA_CONFLICT")
                    .andExpect(jsonPath("$.detail", not(containsString("uq_users_email"))));
        }

        @Test
        void optimisticLockIsConcurrentModification() throws Exception {
            expectProblem(mvc.perform(get("/api/test/errors/stale").with(staff())), 409, "CONCURRENT_MODIFICATION");
        }
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    private JwtRequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("s").claim("realm_access", Map.of("roles", List.of("STAFF"))))
                .authorities(roleConverter);
    }

    record CreateRequest(@NotBlank String name, @Positive int maxSlots) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    @RequestMapping("/api/test/errors")
    @Validated
    static class FaultyEndpoints {

        static final String REGISTER = """
                INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot)
                VALUES ((SELECT id FROM shifts WHERE name = ?), (SELECT id FROM users WHERE keycloak_sub = 's'),
                        ?::tstzrange, 25000)
                """;

        private final JdbcTemplate jdbc;

        FaultyEndpoints(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/manager-only")
        @PreAuthorize("hasRole('MANAGER')")
        String managerOnly() {
            return "ok";
        }

        @PostMapping("/body")
        String body(@Valid @RequestBody CreateRequest request) {
            return "ok";
        }

        @GetMapping("/param")
        String param(@RequestParam @Min(1) int size) {
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException(ErrorCode.REGISTRATION_NOT_OPEN, "Registration opens at 08:00",
                    Map.of("shiftId", 42));
        }

        @GetMapping("/stale")
        String stale() {
            throw new OptimisticLockingFailureException("version mismatch");
        }

        @PostMapping("/sql/overbook")
        void overbook() {
            jdbc.update("UPDATE shifts SET booked_slots = booked_slots + 1 WHERE name = 'A'");
        }

        @PostMapping("/sql/duplicate")
        void duplicate() {
            jdbc.update(REGISTER, "A", "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");
        }

        @PostMapping("/sql/overlap")
        void overlap() {
            jdbc.update(REGISTER, "B", "[2030-01-06 11:00+07, 2030-01-06 15:00+07)");
        }

        @PostMapping("/sql/duplicate-email")
        void duplicateEmail() {
            jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) "
                    + "VALUES ('x', 'X', 's@shiftgrab.test', 'STAFF')");
        }
    }
}
