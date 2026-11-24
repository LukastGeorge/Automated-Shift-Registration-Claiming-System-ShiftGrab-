package com.shiftgrab.registration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/**
 * SG-81: the per-instance sold-out memo, switched on. Every test takes its own block of shift ids, because the memo
 * lives as long as the Spring context while RESTART IDENTITY hands out id 1 again before each test.
 */
@TestPropertySource(properties = "shiftgrab.claims.sold-out-memo=1m")
class SoldOutMemoApiTests extends ApiTestSupport {

    private static long nextIdBlock = 1_000_000;

    /** A one-slot shift whose id no other test of this class (or earlier test) has used. */
    private long soldOutShift() {
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('shifts', 'id'), ?)", Long.class, nextIdBlock);
        nextIdBlock += 1_000;
        return insertOpenShift("Memo", "2030-03-04T01:00:00Z", "2030-03-04T05:00:00Z", 1);
    }

    private ResultActions claim(long shiftId, String sub) throws Exception {
        return mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(token(sub, sub, "STAFF")));
    }

    @Test
    @DisplayName("After the last slot goes, others get SHIFT_FULL and the holder still gets ALREADY_REGISTERED")
    void soldOutAnswers() throws Exception {
        long shiftId = soldOutShift();
        claim(shiftId, "memo-a").andExpect(status().isCreated());

        claim(shiftId, "memo-b").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_FULL"));
        claim(shiftId, "memo-a").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED")); // AC6 holds on the memo path
    }

    @Test
    @DisplayName("A cancellation on this instance clears the memo: the freed slot is claimable at once")
    void cancellationClearsMemo() throws Exception {
        long shiftId = soldOutShift();
        String location = claim(shiftId, "memo-c").andExpect(status().isCreated()).andReturn().getResponse()
                .getHeader("Location");
        claim(shiftId, "memo-d").andExpect(jsonPath("$.code").value("SHIFT_FULL")); // memo now set

        mvc.perform(delete(location.substring(location.indexOf("/api/"))).with(token("memo-c", "memo-c", "STAFF")))
                .andExpect(status().isNoContent());

        claim(shiftId, "memo-d").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Documented trade-off: a slot freed elsewhere is refused until the memo expires, never overbooked")
    void staleMemoOnlyRefuses() throws Exception {
        long shiftId = soldOutShift();
        claim(shiftId, "memo-e").andExpect(status().isCreated());
        claim(shiftId, "memo-f").andExpect(jsonPath("$.code").value("SHIFT_FULL"));

        // Another instance (simulated in SQL) frees capacity; this instance still trusts its memo for its TTL.
        jdbc.update("UPDATE shifts SET max_slots = 2, status = 'OPEN' WHERE id = ?", shiftId);

        claim(shiftId, "memo-f").andExpect(jsonPath("$.code").value("SHIFT_FULL"));
    }
}
