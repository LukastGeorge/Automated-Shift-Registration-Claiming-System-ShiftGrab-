package com.shiftgrab.shift;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shiftgrab.ApiTestSupport;

/** SG-24: filter by date range and status, paginated, sorted by start; staff see only actionable shifts. */
class BrowseShiftsApiTests extends ApiTestSupport {

    @BeforeEach
    void seed() {
        // Inserted out of order to prove sorting; "Tie-b" and "Tie-a" start at the same instant.
        insertShift("Week2", "2030-01-14T08:00:00Z", "2030-01-14T12:00:00Z", 3, 0, "OPEN");
        insertShift("Full", "2030-01-08T08:00:00Z", "2030-01-08T12:00:00Z", 2, 2, "FULL");
        insertShift("Cancelled", "2030-01-09T08:00:00Z", "2030-01-09T12:00:00Z", 3, 0, "CANCELLED");
        insertShift("Tie-b", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 1, "OPEN");
        insertShift("Tie-a", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "OPEN");
        insertShift("Past", "2020-01-07T08:00:00Z", "2020-01-07T12:00:00Z", 3, 0, "OPEN");
    }

    @Test
    @DisplayName("Managers see every shift sorted by start time, ties broken by id")
    void managerSeesAllSorted() throws Exception {
        mvc.perform(get("/api/v1/shifts").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name")
                        .value(contains("Past", "Tie-b", "Tie-a", "Full", "Cancelled", "Week2")))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("Staff see only OPEN/FULL shifts that have not started")
    void staffSeeOnlyActionableShifts() throws Exception {
        mvc.perform(get("/api/v1/shifts").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name").value(contains("Tie-b", "Tie-a", "Full", "Week2")));
    }

    @Test
    @DisplayName("Staff asking for cancelled or past shifts get nothing")
    void staffCannotWidenTheirView() throws Exception {
        mvc.perform(get("/api/v1/shifts").param("status", "CANCELLED").with(staff()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/shifts").param("from", "2019-01-01T00:00:00Z").param("to", "2021-01-01T00:00:00Z")
                        .with(staff()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Filters by status (repeatable) and by the half-open range [from, to)")
    void filtersByStatusAndRange() throws Exception {
        mvc.perform(get("/api/v1/shifts").param("status", "FULL", "CANCELLED").with(manager()))
                .andExpect(jsonPath("$.content[*].name").value(contains("Full", "Cancelled")));
        // from is inclusive (07 08:00), to is exclusive (09 08:00 not included)
        mvc.perform(get("/api/v1/shifts").param("from", "2030-01-07T15:00:00+07:00")
                        .param("to", "2030-01-09T15:00:00+07:00").with(manager()))
                .andExpect(jsonPath("$.content[*].name").value(contains("Tie-b", "Tie-a", "Full")));
    }

    @Test
    @DisplayName("Pages are stable and report totals")
    void paginates() throws Exception {
        mvc.perform(get("/api/v1/shifts").param("page", "1").param("size", "4").with(manager()))
                .andExpect(jsonPath("$.content[*].name").value(contains("Cancelled", "Week2")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(4))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("Invalid paging, status or range is VALIDATION_FAILED / MALFORMED_REQUEST")
    void rejectsInvalidParameters() throws Exception {
        mvc.perform(get("/api/v1/shifts").param("size", "101").with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        mvc.perform(get("/api/v1/shifts").param("from", "2030-01-09T00:00:00Z").param("to", "2030-01-08T00:00:00Z")
                        .with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        mvc.perform(get("/api/v1/shifts").param("status", "BOGUS").with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
