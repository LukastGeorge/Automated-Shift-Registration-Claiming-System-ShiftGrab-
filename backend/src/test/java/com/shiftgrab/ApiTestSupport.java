package com.shiftgrab;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.shiftgrab.shared.security.KeycloakRealmRoleConverter;

/**
 * Base for API integration tests: real PostgreSQL, simulated Keycloak tokens, clean tables per test.
 * Subclasses share one Spring context (same configuration), which keeps the suite fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
public abstract class ApiTestSupport {

    protected static final String MANAGER_SUB = "manager-1";
    protected static final String STAFF_SUB = "staff-1";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ApplicationEvents events;

    @Autowired
    private KeycloakRealmRoleConverter roleConverter;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE shift_registrations, shifts, shift_templates, audit_log, users, event_publication "
                + "RESTART IDENTITY CASCADE");
    }

    protected JwtRequestPostProcessor manager() {
        return token(MANAGER_SUB, "Lan Tran", "MANAGER");
    }

    protected JwtRequestPostProcessor staff() {
        return token(STAFF_SUB, "An Nguyen", "STAFF");
    }

    protected JwtRequestPostProcessor token(String sub, String name, String role) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("name", name)
                        .claim("email", sub + "@shiftgrab.test")
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(roleConverter);
    }

    /** Creates the user row directly, as the first API call of that user would (SG-10 AC4). */
    protected long insertUser(String sub, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (keycloak_sub, full_name, email, role) VALUES (?, ?, ?, ?)
                ON CONFLICT (keycloak_sub) DO UPDATE SET role = EXCLUDED.role RETURNING id
                """, Long.class, sub, sub, sub + "@shiftgrab.test", role);
    }

    /** A shift whose registration is open right now (opened yesterday), so staff can claim it. */
    protected long insertOpenShift(String name, String startAt, String endAt, int maxSlots) {
        long id = insertShift(name, startAt, endAt, maxSlots, 0, "OPEN");
        jdbc.update("UPDATE shifts SET registration_open_at = now() - interval '1 day' WHERE id = ?", id);
        return id;
    }

    /** Inserts a shift bypassing the API, e.g. one in the past that the API would refuse to create. */
    protected long insertShift(String name, String startAt, String endAt, int maxSlots, int bookedSlots,
            String status) {
        long managerId = insertUser(MANAGER_SUB, "MANAGER");
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, booked_slots, status,
                                    created_by)
                VALUES (?, ?::timestamptz, ?::timestamptz, ?::timestamptz - interval '7 days', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, name, startAt, endAt, startAt, maxSlots, bookedSlots, status, managerId);
    }
}
