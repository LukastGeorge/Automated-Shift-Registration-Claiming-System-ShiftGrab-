package com.shiftgrab.registration;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shiftgrab.ApiTestSupport;

/** SG-32: my upcoming and past registrations with this week's confirmed hours. */
class MyShiftsApiTests extends ApiTestSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private long registration(String shiftName, ZonedDateTime start, int hours, String status) {
        long managerId = insertUser(MANAGER_SUB, "MANAGER");
        long staffId = insertUser(STAFF_SUB, "STAFF");
        long shiftId = jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, booked_slots, created_by)
                VALUES (?, ?, ?, ?, 5, 0, ?) RETURNING id
                """, Long.class, shiftName, start.toOffsetDateTime(), start.plusHours(hours).toOffsetDateTime(),
                start.minusDays(30).toOffsetDateTime(), managerId);
        return jdbc.queryForObject("""
                INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot, cancelled_at)
                VALUES (?, ?, ?, tstzrange(?, ?, '[)'), 25000, CASE WHEN ? = 'CANCELLED' THEN now() END) RETURNING id
                """, Long.class, shiftId, staffId, status, start.toOffsetDateTime(),
                start.plusHours(hours).toOffsetDateTime(), status);
    }

    @Test
    @DisplayName("Upcoming soonest first, with shift name, hours and whether it can still be cancelled")
    void listsUpcoming() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        registration("In 5 days", now.plusDays(5), 4, "CONFIRMED");
        registration("In 2 days", now.plusDays(2), 8, "CONFIRMED");
        registration("In 10 hours", now.plusHours(10), 3, "CONFIRMED"); // inside the 24 h cancel deadline
        registration("Cancelled", now.plusDays(3), 4, "CANCELLED");
        registration("Last month", now.minusDays(30), 4, "CONFIRMED");

        mvc.perform(get("/api/v1/me/registrations").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrations.content[*].shiftName")
                        .value(contains("In 10 hours", "In 2 days", "Cancelled", "In 5 days")))
                .andExpect(jsonPath("$.registrations.content[0].cancellable").value(false))
                .andExpect(jsonPath("$.registrations.content[1].cancellable").value(true))
                .andExpect(jsonPath("$.registrations.content[1].hours").value(8.0))
                .andExpect(jsonPath("$.registrations.content[2].cancellable").value(false))
                .andExpect(jsonPath("$.registrations.totalElements").value(4));

        mvc.perform(get("/api/v1/me/registrations").param("status", "CONFIRMED").with(staff()))
                .andExpect(jsonPath("$.registrations.totalElements").value(3));
    }

    @Test
    @DisplayName("Past registrations latest first")
    void listsPast() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        registration("Two weeks ago", now.minusDays(14), 4, "CONFIRMED");
        registration("Yesterday", now.minusDays(1), 4, "CONFIRMED");
        registration("Tomorrow", now.plusDays(1), 4, "CONFIRMED");

        mvc.perform(get("/api/v1/me/registrations").param("scope", "PAST").with(staff()))
                .andExpect(jsonPath("$.registrations.content[*].shiftName").value(contains("Yesterday", "Two weeks ago")));
    }

    @Test
    @DisplayName("currentWeek counts only confirmed hours inside this ISO week (Monday to Sunday, local time)")
    void summarisesCurrentWeek() throws Exception {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime weekStart = monday.atStartOfDay(ZONE);
        registration("Mon", weekStart.plusHours(8), 8, "CONFIRMED");
        registration("Wed", weekStart.plusDays(2).plusHours(8), 6, "CONFIRMED");
        registration("Cancelled Thu", weekStart.plusDays(3).plusHours(8), 8, "CANCELLED");
        registration("Next Mon", weekStart.plusWeeks(1).plusHours(8), 8, "CONFIRMED");
        // Sunday 22:00 of the previous week -> Monday 03:00: only 3 h fall into this week
        registration("Overnight", ZonedDateTime.of(LocalDateTime.of(monday.minusDays(1), java.time.LocalTime.of(22, 0)),
                ZONE), 5, "CONFIRMED");

        mvc.perform(get("/api/v1/me/registrations").with(staff()))
                .andExpect(jsonPath("$.currentWeek.weekStart").value(monday.toString()))
                .andExpect(jsonPath("$.currentWeek.weekEnd").value(monday.plusDays(6).toString()))
                .andExpect(jsonPath("$.currentWeek.confirmedHours").value(17.0))
                .andExpect(jsonPath("$.currentWeek.limitHours").value(40.0))
                .andExpect(jsonPath("$.currentWeek.remainingHours").value(23.0));
    }

    @Test
    @DisplayName("Only my own registrations; a user with none gets an empty page and a full week")
    void onlyMine() throws Exception {
        registration("Mine", ZonedDateTime.now(ZONE).plusDays(2), 4, "CONFIRMED");

        mvc.perform(get("/api/v1/me/registrations").with(token("staff-2", "Binh", "STAFF")))
                .andExpect(jsonPath("$.registrations.totalElements").value(0))
                .andExpect(jsonPath("$.currentWeek.remainingHours").value(40.0));
    }

    @Test
    @DisplayName("Invalid scope or paging is VALIDATION_FAILED")
    void validatesParameters() throws Exception {
        mvc.perform(get("/api/v1/me/registrations").param("scope", "soon").with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scope"));
        mvc.perform(get("/api/v1/me/registrations").param("size", "0").with(staff()))
                .andExpect(status().isBadRequest());
    }
}
