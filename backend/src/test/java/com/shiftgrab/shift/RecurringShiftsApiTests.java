package com.shiftgrab.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-23: recurring shifts from a template — count, atomicity, idempotency, local time and overnight shifts. */
class RecurringShiftsApiTests extends ApiTestSupport {

    /** 2030-01-07 is a Monday; four full weeks end on Sunday 2030-02-03. */
    private static final String WEEKDAYS_4_WEEKS = """
            {"name": "Morning", "weekdays": [1,2,3,4,5], "startTime": "08:00", "endTime": "12:00", "maxSlots": 3,
             "openBefore": "P7D", "validFrom": "2030-01-07", "validTo": "2030-02-03"}
            """;

    @Test
    @DisplayName("Template is stored and returned with local times")
    void createsTemplate() throws Exception {
        createTemplate(WEEKDAYS_4_WEEKS)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/shift-templates/1"))
                .andExpect(jsonPath("$.weekdays").value(contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.startTime").value("08:00:00"))
                .andExpect(jsonPath("$.overnight").value(false))
                .andExpect(jsonPath("$.openBefore").value("PT168H"));

        mvc.perform(get("/api/v1/shift-templates").with(manager())).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/shift-templates/1").with(manager())).andExpect(jsonPath("$.name").value("Morning"));
    }

    @Test
    @DisplayName("AC1: Mon-Fri 08:00-12:00, 3 slots, 4 weeks generates 20 shifts in local time")
    void generatesTwentyShifts() throws Exception {
        long templateId = templateId(createTemplate(WEEKDAYS_4_WEEKS));

        generate(templateId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdShiftIds", hasSize(20)))
                .andExpect(jsonPath("$.skippedExisting").value(0))
                .andExpect(jsonPath("$.skippedPast").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shifts WHERE template_id = ? AND max_slots = 3",
                Integer.class, templateId)).isEqualTo(20);
        // 08:00 in Asia/Ho_Chi_Minh is 01:00 UTC; opening is 7 days earlier
        assertThat(jdbc.queryForList("""
                SELECT to_char(start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || '/' ||
                       to_char(registration_open_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI')
                  FROM shifts ORDER BY start_at LIMIT 2
                """, String.class))
                .containsExactly("2030-01-07 01:00/2029-12-31 01:00", "2030-01-08 01:00/2030-01-01 01:00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shifts WHERE extract(isodow FROM start_at AT TIME ZONE "
                + "'Asia/Ho_Chi_Minh') > 5", Integer.class)).isZero();
        assertThat(events.stream(ShiftsGenerated.class)).singleElement()
                .satisfies(e -> assertThat(e.shiftIds()).hasSize(20));
    }

    @Test
    @DisplayName("AC3: generating again creates nothing and reports the skipped shifts")
    void generationIsIdempotent() throws Exception {
        long templateId = templateId(createTemplate(WEEKDAYS_4_WEEKS));
        generate(templateId).andExpect(status().isOk());

        generate(templateId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdShiftIds", hasSize(0)))
                .andExpect(jsonPath("$.skippedExisting").value(20));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shifts", Integer.class)).isEqualTo(20);
        assertThat(events.stream(ShiftsGenerated.class)).hasSize(1);
    }

    @Test
    @DisplayName("AC2: a failure in the middle leaves no shift behind")
    void generationIsAllOrNothing() throws Exception {
        long templateId = templateId(createTemplate(WEEKDAYS_4_WEEKS));
        // Simulate a failure on the 12th occurrence (a Wednesday in week 3)
        jdbc.execute("""
                CREATE FUNCTION fail_on_jan_23() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.start_at = '2030-01-23 01:00Z' THEN RAISE EXCEPTION 'simulated failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER fail_on_jan_23 BEFORE INSERT ON shifts FOR EACH ROW EXECUTE FUNCTION fail_on_jan_23()");
        try {
            generate(templateId).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("DROP TRIGGER fail_on_jan_23 ON shifts");
            jdbc.execute("DROP FUNCTION fail_on_jan_23()");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shifts", Integer.class)).isZero();
        assertThat(events.stream(ShiftsGenerated.class)).isEmpty();
    }

    @Test
    @DisplayName("Overnight template: end time on the next day")
    void generatesOvernightShifts() throws Exception {
        long templateId = templateId(createTemplate("""
                {"name": "Night", "weekdays": [5], "startTime": "22:00", "endTime": "06:00", "maxSlots": 2,
                 "openBefore": "PT48H", "validFrom": "2030-01-07", "validTo": "2030-01-13"}
                """).andExpect(jsonPath("$.overnight").value(true)));

        generate(templateId).andExpect(jsonPath("$.createdShiftIds", hasSize(1)));

        assertThat(jdbc.queryForObject("""
                SELECT to_char(start_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'Dy HH24:MI') || ' - ' ||
                       to_char(end_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'Dy HH24:MI') FROM shifts
                """, String.class)).isEqualTo("Fri 22:00 - Sat 06:00");
    }

    @Test
    @DisplayName("Occurrences in the past are skipped, not created")
    void skipsPastOccurrences() throws Exception {
        long templateId = templateId(createTemplate("""
                {"name": "Old", "weekdays": [1,2,3,4,5,6,7], "startTime": "08:00", "endTime": "12:00", "maxSlots": 1,
                 "openBefore": "P1D", "validFrom": "2020-01-01", "validTo": "2020-01-10"}
                """));

        generate(templateId)
                .andExpect(jsonPath("$.createdShiftIds", hasSize(0)))
                .andExpect(jsonPath("$.skippedPast").value(10));
    }

    @Test
    @DisplayName("Invalid templates are rejected per field")
    void validatesTemplate() throws Exception {
        createTemplate("""
                {"name": "Bad", "weekdays": [0,8], "startTime": "08:00", "endTime": "21:00", "maxSlots": 1,
                 "openBefore": "PT0S", "validFrom": "2030-02-01", "validTo": "2030-01-01"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(contains("endTime", "openBefore", "validTo", "weekdays")));
        createTemplate("""
                {"name": "Too long", "weekdays": [1], "startTime": "08:00", "endTime": "12:00", "maxSlots": 1,
                 "openBefore": "P1D", "validFrom": "2030-01-01", "validTo": "2031-01-02"}
                """)
                .andExpect(jsonPath("$.errors[0].message").value("date range must not exceed 366 days"));
        createTemplate("{\"name\": \"Empty\", \"weekdays\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(7)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_templates", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Unknown template and staff access are rejected")
    void rejectsUnknownTemplateAndStaff() throws Exception {
        generate(42).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
        mvc.perform(get("/api/v1/shift-templates").with(staff())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/shift-templates/1/generate").with(staff())).andExpect(status().isForbidden());
    }

    private ResultActions createTemplate(String body) throws Exception {
        return mvc.perform(post("/api/v1/shift-templates").with(manager())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions generate(long templateId) throws Exception {
        return mvc.perform(post("/api/v1/shift-templates/{id}/generate", templateId).with(manager()));
    }

    private static long templateId(ResultActions created) throws Exception {
        String location = created.andReturn().getResponse().getHeader("Location");
        return Long.parseLong(List.of(location.split("/")).getLast());
    }
}
