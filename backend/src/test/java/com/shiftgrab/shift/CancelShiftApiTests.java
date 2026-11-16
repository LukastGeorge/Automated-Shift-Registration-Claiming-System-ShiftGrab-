package com.shiftgrab.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.registration.ShiftRegistrationsCancelled;

/** SG-22 / BR-07: cancelling a shift cancels its registrations atomically and tells who was affected. */
class CancelShiftApiTests extends ApiTestSupport {

    private static final String RANGE = "[2030-01-07 08:00Z, 2030-01-07 12:00Z)";

    @Test
    @DisplayName("Shift and its confirmed registrations are cancelled; affected staff are announced")
    void cancelsShiftAndRegistrations() throws Exception {
        long shiftId = insertShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 2, "OPEN");
        long an = insertUser("staff-an", "STAFF");
        long binh = insertUser("staff-binh", "STAFF");
        long chi = insertUser("staff-chi", "STAFF");
        register(shiftId, an, "CONFIRMED");
        register(shiftId, binh, "CONFIRMED");
        register(shiftId, chi, "CANCELLED"); // cancelled earlier by herself: must not be notified again

        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"  Store closed  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.bookedSlots").value(0))
                .andExpect(jsonPath("$.availableSlots").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_registrations WHERE status = 'CONFIRMED'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_registrations WHERE cancelled_at IS NULL", Integer.class)).isZero();
        assertThat(events.stream(ShiftCancelled.class)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("Store closed"));
        assertThat(events.stream(ShiftRegistrationsCancelled.class))
                .containsExactly(new ShiftRegistrationsCancelled(shiftId, List.of(an, binh), "Store closed"));
    }

    @Test
    @DisplayName("Body is optional; a shift without registrations announces an empty list")
    void cancelsEmptyShiftWithoutBody() throws Exception {
        long shiftId = insertShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "OPEN");

        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager())).andExpect(status().isOk());

        assertThat(events.stream(ShiftRegistrationsCancelled.class))
                .containsExactly(new ShiftRegistrationsCancelled(shiftId, List.of(), null));
    }

    @Test
    @DisplayName("Cancelling twice is harmless and publishes nothing the second time")
    void isIdempotent() throws Exception {
        long shiftId = insertShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "CANCELLED");

        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(events.stream(ShiftCancelled.class)).isEmpty();
    }

    @Test
    @DisplayName("Started shifts, unknown shifts and staff are rejected")
    void rejectsInvalidCancellations() throws Exception {
        long started = insertShift("Past", "2020-01-07T08:00:00Z", "2020-01-07T12:00:00Z", 3, 0, "OPEN");

        mvc.perform(post("/api/v1/shifts/{id}/cancel", started).with(manager()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_ALREADY_STARTED"));
        mvc.perform(post("/api/v1/shifts/999/cancel").with(manager()))
                .andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
        mvc.perform(post("/api/v1/shifts/{id}/cancel", started).with(staff())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/shifts/{id}/cancel", started).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void register(long shiftId, long userId, String status) {
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot, cancelled_at)
                VALUES (?, ?, ?, ?::tstzrange, 25000, CASE WHEN ? = 'CANCELLED' THEN now() END)
                """, shiftId, userId, status, RANGE, status);
    }
}
