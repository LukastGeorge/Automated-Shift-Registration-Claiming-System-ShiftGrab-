package com.shiftgrab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0005 layer 3: the database itself rejects invalid states, independently of application code.
 * Each test bypasses the application and writes SQL directly, as a buggy service or a manual fix would.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaConstraintsTests {

    @Autowired
    JdbcTemplate jdbc;

    long managerId;
    long staffId;

    @BeforeEach
    void cleanAndSeed() {
        jdbc.execute("TRUNCATE shift_registrations, shifts, shift_templates, users RESTART IDENTITY CASCADE");
        managerId = insertUser("manager-sub", "manager@shiftgrab.test", "MANAGER");
        staffId = insertUser("staff-sub", "staff@shiftgrab.test", "STAFF");
    }

    @Test
    @DisplayName("BR-01: booked_slots can never exceed max_slots")
    void rejectsOverbooking() {
        long shiftId = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 1);
        jdbc.update("UPDATE shifts SET booked_slots = 1 WHERE id = ?", shiftId);

        assertThatThrownBy(() -> jdbc.update("UPDATE shifts SET booked_slots = 2 WHERE id = ?", shiftId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_shifts_slots");
    }

    @Test
    @DisplayName("BR-02: one active registration per user and shift")
    void rejectsDuplicateActiveRegistration() {
        long shiftId = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 3);
        register(shiftId, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");

        assertThatThrownBy(() -> register(shiftId, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BR-02: re-claiming after cancelling is allowed (fixes the prototype's full UNIQUE constraint)")
    void allowsReclaimAfterCancel() {
        long shiftId = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 3);
        long registrationId = register(shiftId, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");
        jdbc.update("UPDATE shift_registrations SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?",
                registrationId);

        register(shiftId, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");

        assertThat(countRegistrations(staffId)).isEqualTo(2);
    }

    @Test
    @DisplayName("BR-03: overlapping confirmed shifts for the same user are rejected")
    void rejectsOverlappingShifts() {
        long morning = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 3);
        long overlapping = insertShift("2030-01-06 11:00+07", "2030-01-06 15:00+07", 3);
        register(morning, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");

        assertThatThrownBy(() -> register(overlapping, staffId, "[2030-01-06 11:00+07, 2030-01-06 15:00+07)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_registrations_no_overlap");
    }

    @Test
    @DisplayName("BR-03: back-to-back shifts do not overlap (half-open ranges)")
    void allowsAdjacentShifts() {
        long morning = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 3);
        long afternoon = insertShift("2030-01-06 12:00+07", "2030-01-06 16:00+07", 3);
        register(morning, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");

        register(afternoon, staffId, "[2030-01-06 12:00+07, 2030-01-06 16:00+07)");

        assertThat(countRegistrations(staffId)).isEqualTo(2);
    }

    @Test
    @DisplayName("BR-03: a cancelled registration does not block an overlapping claim")
    void cancelledRegistrationDoesNotBlockOverlap() {
        long morning = insertShift("2030-01-06 08:00+07", "2030-01-06 12:00+07", 3);
        long overlapping = insertShift("2030-01-06 11:00+07", "2030-01-06 15:00+07", 3);
        long registrationId = register(morning, staffId, "[2030-01-06 08:00+07, 2030-01-06 12:00+07)");
        jdbc.update("UPDATE shift_registrations SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?",
                registrationId);

        register(overlapping, staffId, "[2030-01-06 11:00+07, 2030-01-06 15:00+07)");

        assertThat(countRegistrations(staffId)).isEqualTo(2);
    }

    private long insertUser(String sub, String email, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (keycloak_sub, full_name, email, role) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, sub, email, email, role);
    }

    private long insertShift(String start, String end, int maxSlots) {
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES ('Test shift', ?::timestamptz, ?::timestamptz, ?::timestamptz - interval '7 days', ?, ?)
                RETURNING id
                """, Long.class, start, end, start, maxSlots, managerId);
    }

    private long register(long shiftId, long userId, String range) {
        return jdbc.queryForObject("""
                INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot)
                VALUES (?, ?, ?::tstzrange, 25000) RETURNING id
                """, Long.class, shiftId, userId, range);
    }

    private int countRegistrations(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM shift_registrations WHERE user_id = ?", Integer.class, userId);
    }
}
