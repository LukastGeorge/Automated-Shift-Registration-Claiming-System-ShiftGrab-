package com.shiftgrab.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-25: managers set hourly rates; past registrations keep their snapshot (BR-10). */
class HourlyRateApiTests extends ApiTestSupport {

    @Test
    @DisplayName("Manager sets a rate; the user sees it; HourlyRateChanged carries old and new values")
    void setsHourlyRate() throws Exception {
        long staffId = insertUser(STAFF_SUB, "STAFF");

        setRate(staffId, "{\"hourlyRate\": 32000.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(staffId))
                .andExpect(jsonPath("$.hourlyRate").value(32000.50));

        mvc.perform(get("/api/v1/me").with(staff())).andExpect(jsonPath("$.hourlyRate").value(32000.50));
        assertThat(events.stream(HourlyRateChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.userId()).isEqualTo(staffId);
            assertThat(e.oldRate()).isEqualByComparingTo("25000");
            assertThat(e.newRate()).isEqualByComparingTo("32000.50");
        });
    }

    @Test
    @DisplayName("BR-10: existing registrations keep the rate snapshotted at claim time")
    void doesNotRewritePastPay() throws Exception {
        long staffId = insertUser(STAFF_SUB, "STAFF");
        long shiftId = insertShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 1, "OPEN");
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot)
                VALUES (?, ?, '[2030-01-07 08:00Z, 2030-01-07 12:00Z)', 25000)
                """, shiftId, staffId);

        setRate(staffId, "{\"hourlyRate\": 40000}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT hourly_rate_snapshot FROM shift_registrations", BigDecimal.class))
                .isEqualByComparingTo("25000");
    }

    @Test
    @DisplayName("Setting the same rate again changes nothing and publishes nothing")
    void sameRateIsNoOp() throws Exception {
        long staffId = insertUser(STAFF_SUB, "STAFF");

        setRate(staffId, "{\"hourlyRate\": 25000.00}").andExpect(status().isOk());

        assertThat(events.stream(HourlyRateChanged.class)).isEmpty();
    }

    @Test
    @DisplayName("Invalid amounts, unknown users and staff callers are rejected")
    void rejectsInvalidRequests() throws Exception {
        long staffId = insertUser(STAFF_SUB, "STAFF");

        for (String body : new String[] { "{\"hourlyRate\": -1}", "{\"hourlyRate\": 10000000.01}",
                "{\"hourlyRate\": 1.234}", "{}" }) {
            setRate(staffId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("hourlyRate"));
        }
        setRate(999, "{\"hourlyRate\": 1}").andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(put("/api/v1/users/{id}/hourly-rate", staffId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hourlyRate\": 99999}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Managers list users by role, sorted by name")
    void listsUsers() throws Exception {
        jdbc.update("INSERT INTO users (keycloak_sub, full_name, email, role) VALUES "
                + "('s-b', 'Binh', 'b@x.test', 'STAFF'), ('s-a', 'An', 'a@x.test', 'STAFF'), "
                + "('m-l', 'Lan', 'l@x.test', 'MANAGER')");

        mvc.perform(get("/api/v1/users").param("role", "STAFF").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName").value(contains("An", "Binh")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/users").param("size", "1").with(manager()))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/v1/users").with(staff())).andExpect(status().isForbidden());
    }

    private ResultActions setRate(long userId, String body) throws Exception {
        return mvc.perform(put("/api/v1/users/{id}/hourly-rate", userId).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
