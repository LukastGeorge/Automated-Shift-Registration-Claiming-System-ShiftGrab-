package com.shiftgrab.shift.internal;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftSlots;

/**
 * Slot counters for the registration module. {@code version} is deliberately not bumped: claims are not manager
 * edits, so they must not invalidate a manager's ETag (SG-21). MANDATORY because a slot change without the
 * matching registration row would break {@code booked_slots = confirmed registrations}.
 */
@Component
class ShiftSlotsAdapter implements ShiftSlots {

    private final ShiftRepository shifts;
    private final JdbcClient jdbc;

    ShiftSlotsAdapter(ShiftRepository shifts, JdbcClient jdbc) {
        this.shifts = shifts;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Shift> findById(long shiftId) {
        return shifts.findById(shiftId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserveSlot(long shiftId) {
        return jdbc.sql("""
                UPDATE shifts
                   SET booked_slots = booked_slots + 1,
                       status = CASE WHEN booked_slots + 1 >= max_slots THEN 'FULL' ELSE 'OPEN' END
                 WHERE id = :id AND status = 'OPEN' AND booked_slots < max_slots
                """)
                .param("id", shiftId)
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseSlot(long shiftId) {
        jdbc.sql("""
                UPDATE shifts SET booked_slots = booked_slots - 1, status = 'OPEN'
                 WHERE id = :id AND status <> 'CANCELLED' AND booked_slots > 0
                """)
                .param("id", shiftId)
                .update();
    }
}
