package com.shiftgrab.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;

import com.shiftgrab.ApiTestSupport;

/** SG-52: every business change leaves exactly one audit entry, written in the same transaction. */
class AuditLogApiTests extends ApiTestSupport {

    private static final String NEW_SHIFT = """
            {"name": "Morning bar", "startAt": "2030-01-07T08:00:00Z", "endAt": "2030-01-07T12:00:00Z",
             "registrationOpenAt": "2026-01-01T00:00:00Z", "maxSlots": 3}
            """;

    @Test
    @DisplayName("Create, edit, claim, cancel registration, cancel shift: who did what, newest first, with details")
    void recordsTheWholeShiftLifecycle() throws Exception {
        long shiftId = createShift();
        mvc.perform(patch("/api/v1/shifts/{id}", shiftId).with(manager()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxSlots\": 5, \"name\": \"Morning bar\"}"))
                .andExpect(status().isOk());
        long registrationId = claim(shiftId);
        mvc.perform(delete("/api/v1/registrations/{id}", registrationId).with(staff()))
                .andExpect(status().isNoContent());
        claim(shiftId);
        mvc.perform(post("/api/v1/shifts/{id}/cancel", shiftId).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Store closed\"}"))
                .andExpect(status().isOk());

        long managerId = idOf(MANAGER_SUB);
        long staffId = idOf(STAFF_SUB);
        mvc.perform(get("/api/v1/audit-log").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.content[*].action").value(Matchers.contains(
                        "SHIFT_REGISTRATIONS_CANCELLED", "SHIFT_CANCELLED", "SHIFT_CLAIMED", "REGISTRATION_CANCELLED",
                        "SHIFT_CLAIMED", "SHIFT_UPDATED", "SHIFT_CREATED")))
                // the edit records only what changed (the name was sent but did not change)
                .andExpect(jsonPath("$.content[5].details.maxSlots.from").value(3))
                .andExpect(jsonPath("$.content[5].details.maxSlots.to").value(5))
                .andExpect(jsonPath("$.content[5].details.name").doesNotExist())
                .andExpect(jsonPath("$.content[5].actorName").value("Lan Tran"))
                .andExpect(jsonPath("$.content[6].details.startAt").value("2030-01-07T08:00:00Z"))
                .andExpect(jsonPath("$.content[4].actorId").value(staffId))
                .andExpect(jsonPath("$.content[4].targetType").value("REGISTRATION"))
                .andExpect(jsonPath("$.content[4].details.shiftId").value(shiftId))
                .andExpect(jsonPath("$.content[1].actorId").value(managerId))
                .andExpect(jsonPath("$.content[1].details.reason").value("Store closed"))
                .andExpect(jsonPath("$.content[0].details.affectedUserIds[0]").value(staffId));
    }

    @Test
    @DisplayName("Templates, generation and hourly rates are audited too")
    void recordsTemplatesAndRates() throws Exception {
        String template = mvc.perform(post("/api/v1/shift-templates").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "Weekday mornings", "weekdays": [1], "startTime": "08:00", "endTime": "12:00",
                                 "maxSlots": 2, "openBefore": "P7D", "validFrom": "2030-01-07", "validTo": "2030-01-20"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long templateId = Long.parseLong(template.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/v1/shift-templates/{id}/generate", templateId).with(manager()))
                .andExpect(status().isOk());
        long staffId = insertUser(STAFF_SUB, "STAFF");
        mvc.perform(put("/api/v1/users/{id}/hourly-rate", staffId).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"hourlyRate\": 30000}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit-log").with(manager()))
                .andExpect(jsonPath("$.content[*].action").value(Matchers.contains(
                        "HOURLY_RATE_CHANGED", "SHIFTS_GENERATED", "TEMPLATE_CREATED")))
                .andExpect(jsonPath("$.content[0].targetId").value(staffId))
                .andExpect(jsonPath("$.content[0].details.hourlyRate.from").value(25000.0))
                .andExpect(jsonPath("$.content[0].details.hourlyRate.to").value(30000))
                .andExpect(jsonPath("$.content[1].details.created").value(2))
                .andExpect(jsonPath("$.content[2].details.name").value("Weekday mornings"));
    }

    @Test
    @DisplayName("A refused change leaves no audit entry (same transaction)")
    void refusedChangeIsNotAudited() throws Exception {
        long shiftId = insertShift("Full", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 1, 1, "FULL");

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff())).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Filters by actor, action and time; staff cannot read the log")
    void filters() throws Exception {
        long shiftId = createShift();
        claim(shiftId);
        long staffId = idOf(STAFF_SUB);

        mvc.perform(get("/api/v1/audit-log").param("actorId", String.valueOf(staffId)).with(manager()))
                .andExpect(jsonPath("$.content[*].action").value(Matchers.contains("SHIFT_CLAIMED")));
        mvc.perform(get("/api/v1/audit-log").param("action", "SHIFT_CREATED").with(manager()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/audit-log").param("from", "2099-01-01T00:00:00Z").with(manager()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/audit-log").param("from", "2030-01-02T00:00:00Z").param("to", "2030-01-01T00:00:00Z")
                .with(manager())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit-log").with(staff())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The audit log is append-only, even for the table owner (NFR-10)")
    void appendOnly() throws Exception {
        createShift();

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'TAMPERED'"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log", String.class)).isEqualTo(List.of("SHIFT_CREATED"));
    }

    private long createShift() throws Exception {
        String body = mvc.perform(post("/api/v1/shifts").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content(NEW_SHIFT)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long claim(long shiftId) throws Exception {
        String body = mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    private long idOf(String sub) {
        return jdbc.queryForObject("SELECT id FROM users WHERE keycloak_sub = ?", Long.class, sub);
    }
}
