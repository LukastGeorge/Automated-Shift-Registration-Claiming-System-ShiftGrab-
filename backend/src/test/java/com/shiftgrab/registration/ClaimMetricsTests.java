package com.shiftgrab.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.shiftgrab.ApiTestSupport;

import io.micrometer.core.instrument.MeterRegistry;

/** SG-72: every claim outcome is counted, so a dashboard can show what a flash sale looked like. */
class ClaimMetricsTests extends ApiTestSupport {

    @Autowired
    MeterRegistry meters;

    @Test
    @DisplayName("Claims are counted per outcome: CLAIMED or the error code")
    void countsOutcomes() throws Exception {
        double claimedBefore = count("CLAIMED");
        double fullBefore = count("SHIFT_FULL");
        long shiftId = insertOpenShift("Last slot", "2030-01-07T01:00:00Z", "2030-01-07T05:00:00Z", 1);

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff())).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(token("staff-2", "Binh Le", "STAFF")))
                .andExpect(status().isConflict());

        assertThat(count("CLAIMED") - claimedBefore).isEqualTo(1);
        assertThat(count("SHIFT_FULL") - fullBefore).isEqualTo(1);
    }

    private double count(String result) {
        var counter = meters.find("shiftgrab.claims").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
