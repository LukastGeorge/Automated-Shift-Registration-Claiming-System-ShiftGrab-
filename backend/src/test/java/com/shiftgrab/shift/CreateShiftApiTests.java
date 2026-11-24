package com.shiftgrab.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-20: create a shift, and read it back (visibility rules shared with SG-24). */
class CreateShiftApiTests extends ApiTestSupport {

    private static final String VALID = """
            {"name": "Morning", "startAt": "2030-01-07T08:00:00+07:00", "endAt": "2030-01-07T12:00:00+07:00",
             "registrationOpenAt": "2030-01-01T08:00:00+07:00", "maxSlots": 4}
            """;

    @Test
    @DisplayName("AC1: valid shift is created OPEN with 0 booked slots, times normalised to UTC")
    void createsShift() throws Exception {
        create(VALID)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/shifts/1"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.name").value("Morning"))
                .andExpect(jsonPath("$.startAt").value("2030-01-07T01:00:00Z"))
                .andExpect(jsonPath("$.endAt").value("2030-01-07T05:00:00Z"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bookedSlots").value(0))
                .andExpect(jsonPath("$.availableSlots").value(4))
                .andExpect(jsonPath("$.templateId").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT u.keycloak_sub FROM shifts s JOIN users u ON u.id = s.created_by",
                String.class)).isEqualTo(MANAGER_SUB);
        assertThat(events.stream(ShiftCreated.class)).containsExactly(new ShiftCreated(1, 1));
    }

    @Test
    @DisplayName("AC2: missing and out-of-range fields are reported per field")
    void rejectsInvalidFields() throws Exception {
        create("""
                {"name": " ", "startAt": "2030-01-07T08:00:00+07:00", "registrationOpenAt": "2030-01-01T08:00:00Z",
                 "maxSlots": 0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("endAt", "maxSlots", "name")));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SG-92: a name with a line break is refused (it would reach e-mail subjects, CSV and logs)")
    void rejectsControlCharactersInName() throws Exception {
        create("""
                {"name": "Morning\\r\\nBcc: attacker@example.com", "startAt": "2030-01-07T08:00:00+07:00",
                 "endAt": "2030-01-07T12:00:00+07:00", "registrationOpenAt": "2030-01-01T08:00:00+07:00", "maxSlots": 4}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain line breaks or control characters"));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("AC2: end before start and opening after start are rejected")
    void rejectsInconsistentTimes() throws Exception {
        create("""
                {"name": "Bad", "startAt": "2030-01-07T08:00:00Z", "endAt": "2030-01-07T07:00:00Z",
                 "registrationOpenAt": "2030-01-07T09:00:00Z", "maxSlots": 1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must be after startAt"))
                .andExpect(jsonPath("$.errors[1].field").value("registrationOpenAt"));
    }

    @Test
    @DisplayName("A shift must start in the future")
    void rejectsPastShift() throws Exception {
        create("""
                {"name": "Past", "startAt": "2020-01-07T08:00:00Z", "endAt": "2020-01-07T12:00:00Z",
                 "registrationOpenAt": "2020-01-01T08:00:00Z", "maxSlots": 1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startAt"));
    }

    @Test
    @DisplayName("A shift cannot be longer than 12 hours; exactly 12 hours is allowed")
    void limitsDuration() throws Exception {
        create("""
                {"name": "Long", "startAt": "2030-01-07T06:00:00Z", "endAt": "2030-01-07T18:01:00Z",
                 "registrationOpenAt": "2030-01-01T08:00:00Z", "maxSlots": 1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("shift must not be longer than 12 hours"));
        create("""
                {"name": "Twelve", "startAt": "2030-01-07T06:00:00Z", "endAt": "2030-01-07T18:00:00Z",
                 "registrationOpenAt": "2030-01-01T08:00:00Z", "maxSlots": 1}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Staff cannot create shifts")
    void staffIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/shifts").with(staff()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("GET returns the shift with its version as ETag")
    void readsShift() throws Exception {
        long id = insertShift("Evening", "2030-01-07T18:00:00Z", "2030-01-07T22:00:00Z", 3, 3, "FULL");

        mvc.perform(get("/api/v1/shifts/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("FULL"))
                .andExpect(jsonPath("$.availableSlots").value(0));
    }

    @Test
    @DisplayName("Staff get 404 for cancelled or started shifts; managers still see them")
    void hidesNonActionableShiftsFromStaff() throws Exception {
        long cancelled = insertShift("Cancelled", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "CANCELLED");
        long past = insertShift("Past", "2020-01-07T08:00:00Z", "2020-01-07T12:00:00Z", 3, 0, "OPEN");

        for (long id : new long[] { cancelled, past }) {
            mvc.perform(get("/api/v1/shifts/{id}", id).with(staff()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
            mvc.perform(get("/api/v1/shifts/{id}", id).with(manager())).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/shifts/{id}", cancelled).with(manager()))
                .andExpect(jsonPath("$.availableSlots").value(0));
    }

    @Test
    @DisplayName("Unknown shift is SHIFT_NOT_FOUND")
    void unknownShift() throws Exception {
        mvc.perform(get("/api/v1/shifts/999").with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/v1/shifts").with(manager()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM shifts", Integer.class);
    }
}
