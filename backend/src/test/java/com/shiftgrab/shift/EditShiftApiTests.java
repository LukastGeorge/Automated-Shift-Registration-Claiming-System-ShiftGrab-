package com.shiftgrab.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-21: edit a shift safely — BR-08, BR-09, optimistic locking via If-Match. */
class EditShiftApiTests extends ApiTestSupport {

    private static final String START = "2030-01-07T08:00:00Z";
    private static final String END = "2030-01-07T12:00:00Z";

    @Test
    @DisplayName("Name and maxSlots change; version and ETag advance; ShiftChanged is published")
    void editsNameAndSlots() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        edit(id, "\"0\"", "{\"name\": \"Morning shift\", \"maxSlots\": 5}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name").value("Morning shift"))
                .andExpect(jsonPath("$.maxSlots").value(5))
                .andExpect(jsonPath("$.startAt").value(START));

        assertThat(events.stream(ShiftChanged.class)).singleElement()
                .satisfies(e -> assertThat(e.timesChanged()).isFalse());
    }

    @Test
    @DisplayName("Times can change while nobody is registered")
    void editsTimesWithoutRegistrations() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        edit(id, "\"0\"", "{\"startAt\": \"2030-01-07T09:00:00Z\", \"endAt\": \"2030-01-07T13:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2030-01-07T09:00:00Z"));
        assertThat(events.stream(ShiftChanged.class)).singleElement()
                .satisfies(e -> assertThat(e.timesChanged()).isTrue());
    }

    @Test
    @DisplayName("BR-09: times are frozen once staff registered, other fields are not")
    void freezesTimesOnceBooked() throws Exception {
        long id = insertShift("Morning", START, END, 3, 1, "OPEN");

        edit(id, "\"0\"", "{\"endAt\": \"2030-01-07T13:00:00Z\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_HAS_REGISTRATIONS"))
                .andExpect(jsonPath("$.bookedSlots").value(1));
        edit(id, "\"0\"", "{\"name\": \"Renamed\", \"registrationOpenAt\": \"2029-12-31T08:00:00Z\"}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BR-08 and BR-11: maxSlots cannot go below booked; status follows occupancy")
    void enforcesSlotFloorAndRecomputesStatus() throws Exception {
        long id = insertShift("Morning", START, END, 4, 2, "OPEN");

        edit(id, "\"0\"", "{\"maxSlots\": 1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_HAS_REGISTRATIONS"));
        edit(id, "\"0\"", "{\"maxSlots\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULL"))
                .andExpect(jsonPath("$.availableSlots").value(0));
        edit(id, "\"1\"", "{\"maxSlots\": 3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("Second of two concurrent editors gets CONCURRENT_MODIFICATION")
    void detectsConcurrentEdits() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");
        JwtRequestPostProcessor secondManager = token("manager-2", "Minh Le", "MANAGER");

        edit(id, "\"0\"", "{\"name\": \"First wins\"}").andExpect(status().isOk());
        mvc.perform(patch("/api/v1/shifts/{id}", id).with(secondManager).header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Second loses\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertThat(jdbc.queryForObject("SELECT name FROM shifts WHERE id = ?", String.class, id)).isEqualTo("First wins");
    }

    @Test
    @DisplayName("If-Match is required; garbage never matches")
    void requiresIfMatch() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        edit(id, null, "{\"name\": \"x\"}")
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        edit(id, "\"abc\"", "{\"name\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("Cancelled or started shifts cannot be edited")
    void rejectsNonEditableShifts() throws Exception {
        long cancelled = insertShift("Cancelled", START, END, 3, 0, "CANCELLED");
        long started = insertShift("Past", "2020-01-07T08:00:00Z", "2020-01-07T12:00:00Z", 3, 0, "OPEN");

        edit(cancelled, "\"0\"", "{\"name\": \"x\"}").andExpect(jsonPath("$.code").value("SHIFT_CANCELLED"));
        edit(started, "\"0\"", "{\"name\": \"x\"}").andExpect(jsonPath("$.code").value("SHIFT_ALREADY_STARTED"));
        edit(999, "\"0\"", "{\"name\": \"x\"}").andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Edited values are validated like on creation")
    void validatesEditedValues() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        edit(id, "\"0\"", "{\"name\": \"  \", \"maxSlots\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("maxSlots", "name")));
        edit(id, "\"0\"", "{\"endAt\": \"2030-01-07T07:00:00Z\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endAt"));
    }

    @Test
    @DisplayName("An edit that changes nothing keeps the version and publishes nothing")
    void noOpEdit() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        edit(id, "\"0\"", "{\"name\": \"Morning\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        assertThat(events.stream(ShiftChanged.class)).isEmpty();
    }

    @Test
    @DisplayName("Staff cannot edit shifts")
    void staffIsForbidden() throws Exception {
        long id = insertShift("Morning", START, END, 3, 0, "OPEN");

        mvc.perform(patch("/api/v1/shifts/{id}", id).with(staff()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions edit(long id, String ifMatch, String body) throws Exception {
        var request = patch("/api/v1/shifts/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }
}
