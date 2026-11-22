package com.shiftgrab.realtime.internal;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the current slot counters together with the database clock. A read model of the {@code shifts} table:
 * reading the latest state (instead of trusting the event's payload) makes every update self-correcting.
 */
@Component
class SlotReader {

    private final JdbcClient jdbc;

    SlotReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SlotUpdate> read(long shiftId) {
        return jdbc.sql("""
                SELECT id, booked_slots, max_slots, status, clock_timestamp() AS observed_at
                  FROM shifts WHERE id = :id
                """)
                .param("id", shiftId)
                .query((rs, row) -> {
                    String status = rs.getString("status");
                    int max = rs.getInt("max_slots");
                    int booked = rs.getInt("booked_slots");
                    return new SlotUpdate(rs.getLong("id"), booked, max,
                            "CANCELLED".equals(status) ? 0 : max - booked, status,
                            rs.getObject("observed_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }
}
