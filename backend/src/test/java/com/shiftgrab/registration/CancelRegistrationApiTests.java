package com.shiftgrab.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-31 / BR-06 / BR-11: cancel my registration before the deadline; the slot is released at once. */
class CancelRegistrationApiTests extends ApiTestSupport {

    /** Claimable shift starting {@code startsIn} from now (SQL interval), registration already open. */
    private long shiftStartingIn(String startsIn, int maxSlots) {
        long managerId = insertUser(MANAGER_SUB, "MANAGER");
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES ('S', now() + ?::interval, now() + ?::interval + interval '4 hours', now() - interval '1 day',
                        ?, ?) RETURNING id
                """, Long.class, startsIn, startsIn, maxSlots, managerId);
    }

    private long claim(long shiftId) throws Exception {
        String location = mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions cancel(long registrationId) throws Exception {
        return mvc.perform(delete("/api/v1/registrations/{id}", registrationId).with(staff()));
    }

    private String shiftState(long shiftId) {
        return jdbc.queryForObject("SELECT status || ' ' || booked_slots FROM shifts WHERE id = ?", String.class,
                shiftId);
    }

    @Test
    @DisplayName("Before the deadline: 204, slot released, FULL shift reopens, RegistrationCancelled published")
    void cancelsAndReleasesSlot() throws Exception {
        long shiftId = shiftStartingIn("3 days", 1);
        long registrationId = claim(shiftId);
        assertThat(shiftState(shiftId)).isEqualTo("FULL 1");

        cancel(registrationId).andExpect(status().isNoContent());

        assertThat(shiftState(shiftId)).isEqualTo("OPEN 0");
        assertThat(jdbc.queryForObject("SELECT status FROM shift_registrations WHERE id = ?", String.class,
                registrationId)).isEqualTo("CANCELLED");
        assertThat(events.stream(RegistrationCancelled.class)).singleElement()
                .satisfies(e -> assertThat(e.shiftId()).isEqualTo(shiftId));
    }

    @Test
    @DisplayName("Cancelling twice releases the slot only once")
    void isIdempotent() throws Exception {
        long shiftId = shiftStartingIn("3 days", 3);
        long registrationId = claim(shiftId);
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(token("staff-2", "Binh", "STAFF")))
                .andExpect(status().isCreated());

        cancel(registrationId).andExpect(status().isNoContent());
        cancel(registrationId).andExpect(status().isNoContent());

        assertThat(shiftState(shiftId)).isEqualTo("OPEN 1"); // staff-2 still holds a slot
        assertThat(events.stream(RegistrationCancelled.class)).hasSize(1);
    }

    @Test
    @DisplayName("BR-06: within 24 h of the start is CANCEL_DEADLINE_PASSED and nothing changes")
    void rejectsAfterDeadline() throws Exception {
        long shiftId = shiftStartingIn("2 hours", 3);
        long registrationId = claim(shiftId);

        cancel(registrationId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.cancelDeadline").isNotEmpty());
        assertThat(shiftState(shiftId)).isEqualTo("OPEN 1");
    }

    @Test
    @DisplayName("After cancelling, the shift can be claimed again (BR-02)")
    void canReclaim() throws Exception {
        long shiftId = shiftStartingIn("3 days", 1);
        cancel(claim(shiftId)).andExpect(status().isNoContent());

        claim(shiftId);
        assertThat(shiftState(shiftId)).isEqualTo("FULL 1");
    }

    @Test
    @DisplayName("Someone else's or an unknown registration is REGISTRATION_NOT_FOUND; managers cannot use it")
    void onlyOwnerCanCancel() throws Exception {
        long shiftId = shiftStartingIn("3 days", 3);
        long registrationId = claim(shiftId);

        mvc.perform(delete("/api/v1/registrations/{id}", registrationId).with(token("staff-2", "Binh", "STAFF")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
        cancel(999).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/registrations/{id}", registrationId).with(manager()))
                .andExpect(status().isForbidden());
        assertThat(shiftState(shiftId)).isEqualTo("OPEN 1");
    }

    @Test
    @DisplayName("A registration already cancelled by a shift cancellation is a no-op, not an error")
    void cancelledByShiftIsNoOp() throws Exception {
        long shiftId = shiftStartingIn("3 days", 3);
        long registrationId = claim(shiftId);
        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager())).andExpect(status().isOk());

        cancel(registrationId).andExpect(status().isNoContent());
        assertThat(shiftState(shiftId)).isEqualTo("CANCELLED 0");
    }
}
