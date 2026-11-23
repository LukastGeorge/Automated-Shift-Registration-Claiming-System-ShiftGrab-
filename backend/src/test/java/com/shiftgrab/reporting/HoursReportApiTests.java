package com.shiftgrab.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shiftgrab.ApiTestSupport;

/** SG-50 with the Sprint 6 decisions: ended shifts only, whole shift on its start day, staff without shifts shown. */
class HoursReportApiTests extends ApiTestSupport {

    @Test
    @DisplayName("October: per staff shifts, hours and pay at the snapshotted rate, with a totals row")
    void monthlyReport() throws Exception {
        long an = insertUser("staff-an", "STAFF");
        long binh = insertUser("staff-binh", "STAFF");
        insertUser("staff-chi", "STAFF"); // no shifts: listed with zeros
        insertUser("manager-1", "MANAGER"); // managers are not on the report
        jdbc.update("UPDATE users SET full_name = CASE keycloak_sub WHEN 'staff-an' THEN 'An Nguyen' "
                + "WHEN 'staff-binh' THEN 'Binh Le' WHEN 'staff-chi' THEN 'Chi Pham' ELSE full_name END");

        registration(an, "2026-10-05T08:00:00+07:00", "2026-10-05T12:00:00+07:00", "25000", "CONFIRMED"); // 4 h
        // overnight, starts on the last day of the period: counts wholly in October
        registration(an, "2026-10-31T22:00:00+07:00", "2026-11-01T06:00:00+07:00", "30000", "CONFIRMED"); // 8 h
        // overnight that starts on 30 September: belongs to September even though it ends in October
        registration(an, "2026-09-30T22:00:00+07:00", "2026-10-01T06:00:00+07:00", "25000", "CONFIRMED");
        registration(an, "2026-10-12T08:00:00+07:00", "2026-10-12T12:00:00+07:00", "25000", "CANCELLED");
        registration(binh, "2026-10-10T09:00:00+07:00", "2026-10-10T10:30:00+07:00", "25000", "CONFIRMED"); // 1.5 h
        // A later rate change does not alter pay already earned (BR-10)
        jdbc.update("UPDATE users SET hourly_rate = 99000");

        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.rows[*].fullName").value(Matchers.contains("An Nguyen", "Binh Le", "Chi Pham")))
                .andExpect(jsonPath("$.rows[0].shifts").value(2))
                .andExpect(jsonPath("$.rows[0].hours").value(12.0))
                .andExpect(jsonPath("$.rows[0].pay").value(340000))
                .andExpect(jsonPath("$.rows[1].hours").value(1.5))
                .andExpect(jsonPath("$.rows[1].pay").value(37500))
                .andExpect(jsonPath("$.rows[2].shifts").value(0))
                .andExpect(jsonPath("$.rows[2].hours").value(0))
                .andExpect(jsonPath("$.rows[2].pay").value(0))
                .andExpect(jsonPath("$.totals.shifts").value(3))
                .andExpect(jsonPath("$.totals.hours").value(13.5))
                .andExpect(jsonPath("$.totals.pay").value(377500));
    }

    @Test
    @DisplayName("A shift that has not ended yet is not on the report, even inside the period")
    void onlyEndedShifts() throws Exception {
        long an = insertUser("staff-an", "STAFF");
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot)
                VALUES (?, ?, 'CONFIRMED', tstzrange(now() - interval '1 hour', now() + interval '1 hour'), 25000)
                """, insertShift("Now", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 3, 1, "OPEN"), an);

        mvc.perform(get("/api/v1/reports/hours").param("from", "2020-01-01").param("to", "2020-12-31").with(manager()))
                .andExpect(jsonPath("$.totals.shifts").value(0));
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-01-01").param("to", "2026-12-31").with(manager()))
                .andExpect(jsonPath("$.totals.shifts").value(0))
                .andExpect(jsonPath("$.rows[0].hours").value(0));
    }

    @Test
    @DisplayName("Period checks, and only managers can read it")
    void validationAndAccess() throws Exception {
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-31").param("to", "2026-10-01").with(manager()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 366 days, both ends included, is the maximum; one more day is refused
        mvc.perform(get("/api/v1/reports/hours").param("from", "2025-01-01").param("to", "2026-01-01").with(manager()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/reports/hours").param("from", "2025-01-01").param("to", "2026-01-02").with(manager()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/hours").param("from", "2025-01-01").param("to", "2025-12-31").with(manager()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/reports/hours").param("from", "2026-10-01").param("to", "2026-10-31").with(staff()))
                .andExpect(status().isForbidden());
    }

    private void registration(long userId, String start, String end, String rate, String status) {
        long shiftId = insertShift("Shift", start, end, 5, 0, "OPEN");
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot, cancelled_at)
                VALUES (?, ?, ?, tstzrange(?::timestamptz, ?::timestamptz), ?::numeric,
                        CASE WHEN ? = 'CANCELLED' THEN now() END)
                """, shiftId, userId, status, start, end, rate, status);
    }
}
