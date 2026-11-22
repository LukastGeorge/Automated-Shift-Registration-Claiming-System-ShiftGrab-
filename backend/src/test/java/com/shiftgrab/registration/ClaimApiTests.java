package com.shiftgrab.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.shiftgrab.ApiTestSupport;

/** SG-30 AC1–AC6 and BR-04/BR-05 (AC7, concurrency, lives in SG-34's suite). */
class ClaimApiTests extends ApiTestSupport {

    /** Registration opened long ago, shift far in the future: claimable unless a test says otherwise. */
    private long openShift(String name, String start, String end, int maxSlots) {
        long managerId = insertUser(MANAGER_SUB, "MANAGER");
        return jdbc.queryForObject("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, created_by)
                VALUES (?, ?::timestamptz, ?::timestamptz, '2020-01-01Z', ?, ?) RETURNING id
                """, Long.class, name, start, end, maxSlots, managerId);
    }

    /** Confirmed registration created behind the API, to set up a staff member's existing week. */
    private void holds(String userSub, long shiftId) {
        long userId = insertUser(userSub, "STAFF");
        jdbc.update("""
                INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot)
                SELECT id, ?, tstzrange(start_at, end_at, '[)'), 25000 FROM shifts WHERE id = ?
                """, userId, shiftId);
        jdbc.update("UPDATE shifts SET booked_slots = booked_slots + 1 WHERE id = ?", shiftId);
    }

    private ResultActions claim(long shiftId) throws Exception {
        return mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()));
    }

    private int booked(long shiftId) {
        return jdbc.queryForObject("SELECT booked_slots FROM shifts WHERE id = ?", Integer.class, shiftId);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("AC1: 201, CONFIRMED, booked_slots + 1, rate snapshotted, ShiftClaimed published")
        void claimsSlot() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
            long staffId = insertUser(STAFF_SUB, "STAFF");
            jdbc.update("UPDATE users SET hourly_rate = 31000 WHERE id = ?", staffId);

            claim(shiftId)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/registrations/1"))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.shiftId").value(shiftId))
                    .andExpect(jsonPath("$.startAt").value("2030-01-07T08:00:00Z"))
                    .andExpect(jsonPath("$.hourlyRateSnapshot").value(31000.00));

            assertThat(booked(shiftId)).isEqualTo(1);
            assertThat(events.stream(ShiftClaimed.class)).containsExactly(new ShiftClaimed(1, shiftId, staffId));
        }

        @Test
        @DisplayName("BR-11: taking the last slot makes the shift FULL; the version (manager ETag) is untouched")
        void lastSlotMakesShiftFull() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 2);
            holds("other", shiftId);

            claim(shiftId).andExpect(status().isCreated());

            assertThat(jdbc.queryForMap("SELECT status, booked_slots, version FROM shifts WHERE id = ?", shiftId))
                    .containsEntry("status", "FULL").containsEntry("booked_slots", 2).containsEntry("version", 0L);
        }

        @Test
        @DisplayName("BR-02: after cancelling, the same shift can be claimed again")
        void reclaimAfterCancel() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
            long staffId = insertUser(STAFF_SUB, "STAFF");
            jdbc.update("""
                    INSERT INTO shift_registrations (shift_id, user_id, status, time_range, hourly_rate_snapshot,
                                                     cancelled_at)
                    VALUES (?, ?, 'CANCELLED', '[2030-01-07 08:00Z, 2030-01-07 12:00Z)', 25000, now())
                    """, shiftId, staffId);

            claim(shiftId).andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("Rejections")
    class Rejections {

        @Test
        @DisplayName("AC2: full shift is SHIFT_FULL")
        void fullShift() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 1);
            holds("other", shiftId);
            jdbc.update("UPDATE shifts SET status = 'FULL' WHERE id = ?", shiftId);

            claim(shiftId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_FULL"));
            assertThat(booked(shiftId)).isEqualTo(1);
        }

        @Test
        @DisplayName("Layer 2: even if status still says OPEN, the conditional update refuses a slot that is not free")
        void conditionalUpdateIsTheRealGuard() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 1);
            holds("other", shiftId); // booked 1/1 but status left OPEN, as if a check raced with another claim

            claim(shiftId).andExpect(jsonPath("$.code").value("SHIFT_FULL"));
            assertThat(booked(shiftId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_registrations WHERE shift_id = ?",
                    Integer.class, shiftId)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC3: before the opening time is REGISTRATION_NOT_OPEN")
        void notOpenYet() throws Exception {
            long shiftId = insertShift("Later", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3, 0, "OPEN");

            claim(shiftId)
                    .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_OPEN"))
                    .andExpect(jsonPath("$.registrationOpenAt").value("2029-12-31T08:00:00Z"));
        }

        @Test
        @DisplayName("AC4: overlapping a held shift is SHIFT_OVERLAP; back-to-back is fine")
        void overlap() throws Exception {
            long morning = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
            long overlapping = openShift("Late morning", "2030-01-07T11:00:00Z", "2030-01-07T15:00:00Z", 3);
            long afternoon = openShift("Afternoon", "2030-01-07T12:00:00Z", "2030-01-07T16:00:00Z", 3);
            holds(STAFF_SUB, morning);

            claim(overlapping).andExpect(jsonPath("$.code").value("SHIFT_OVERLAP"));
            claim(afternoon).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("AC6: claiming a shift you hold is ALREADY_REGISTERED and changes nothing")
        void alreadyRegistered() throws Exception {
            long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
            claim(shiftId).andExpect(status().isCreated());

            claim(shiftId).andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));
            assertThat(booked(shiftId)).isEqualTo(1);
        }

        @Test
        @DisplayName("Cancelled, started and unknown shifts; managers cannot claim")
        void otherRejections() throws Exception {
            long cancelled = openShift("Cancelled", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
            jdbc.update("UPDATE shifts SET status = 'CANCELLED' WHERE id = ?", cancelled);
            long started = openShift("Past", "2021-01-07T08:00:00Z", "2021-01-07T12:00:00Z", 3);

            claim(cancelled).andExpect(jsonPath("$.code").value("SHIFT_CANCELLED"));
            claim(started).andExpect(jsonPath("$.code").value("SHIFT_ALREADY_STARTED"));
            claim(999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
            mvc.perform(post("/api/v1/shifts/{id}/registrations", started).with(manager()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("BR-04 weekly limit (40 h, ISO weeks in Asia/Ho_Chi_Minh)")
    class WeeklyLimit {

        /** Four 9-hour shifts on Mon-Thu of the week of 2030-01-07 (local time): 36 h. */
        private void thirtySixHoursThisWeek() {
            for (int day = 7; day <= 10; day++) {
                holds(STAFF_SUB, openShift("Day " + day, "2030-01-%02dT08:00:00+07:00".formatted(day),
                        "2030-01-%02dT17:00:00+07:00".formatted(day), 5));
            }
        }

        @Test
        @DisplayName("AC5: 36 h + 5 h is over 40 h -> WEEKLY_LIMIT_EXCEEDED; 36 h + 4 h exactly is allowed")
        void limitIsInclusive() throws Exception {
            thirtySixHoursThisWeek();
            long fiveHours = openShift("Fri long", "2030-01-11T08:00:00+07:00", "2030-01-11T13:00:00+07:00", 3);
            long fourHours = openShift("Fri short", "2030-01-11T14:00:00+07:00", "2030-01-11T18:00:00+07:00", 3);

            claim(fiveHours)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WEEKLY_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.weekStart").value("2030-01-07"))
                    .andExpect(jsonPath("$.bookedHours").value(36.0))
                    .andExpect(jsonPath("$.limitHours").value(40.0));
            claim(fourHours).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Hours in another week do not count")
        void otherWeeksDoNotCount() throws Exception {
            thirtySixHoursThisWeek();
            long nextWeek = openShift("Next Mon", "2030-01-14T08:00:00+07:00", "2030-01-14T17:00:00+07:00", 3);

            claim(nextWeek).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Overnight Sun 22:00 -> Mon 06:00: 2 h count in this week, 6 h in the next")
        void overnightShiftIsSplitAcrossWeeks() throws Exception {
            thirtySixHoursThisWeek();
            holds(STAFF_SUB, openShift("Fri", "2030-01-11T08:00:00+07:00", "2030-01-11T11:00:00+07:00", 3)); // 39 h
            long overnight = openShift("Sun night", "2030-01-13T22:00:00+07:00", "2030-01-14T06:00:00+07:00", 3);

            // 39 h + 2 h = 41 h in the week of 2030-01-07, although the next week would only get 6 h
            claim(overnight).andExpect(jsonPath("$.code").value("WEEKLY_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.weekStart").value("2030-01-07"));

            jdbc.update("""
                    UPDATE shift_registrations SET status = 'CANCELLED', cancelled_at = now()
                     WHERE shift_id = (SELECT id FROM shifts WHERE name = 'Fri')
                    """); // back to 36 h: 36 + 2 = 38 h this week, 6 h next week
            claim(overnight).andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("BR-10: the snapshot keeps the rate at claim time")
    void snapshotIsCopied() throws Exception {
        long shiftId = openShift("Morning", "2030-01-07T08:00:00Z", "2030-01-07T12:00:00Z", 3);
        claim(shiftId).andExpect(status().isCreated());

        jdbc.update("UPDATE users SET hourly_rate = 99000 WHERE keycloak_sub = ?", STAFF_SUB);

        assertThat(jdbc.queryForObject("SELECT hourly_rate_snapshot FROM shift_registrations", BigDecimal.class))
                .isEqualByComparingTo("25000");
    }
}
