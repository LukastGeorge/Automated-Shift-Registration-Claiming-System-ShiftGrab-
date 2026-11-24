package com.shiftgrab.shift.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    public Map<Long, Shift> findAllById(Collection<Long> shiftIds) {
        if (shiftIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + ShiftRepository.COLUMNS + " FROM shifts WHERE id IN (:ids)")
                .param("ids", shiftIds)
                .query(ShiftRepository::map)
                .stream()
                .collect(Collectors.toMap(Shift::id, Function.identity()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Shift> tryReserveSlot(long shiftId, long expectedVersion) {
        return jdbc.sql("""
                UPDATE shifts
                   SET booked_slots = booked_slots + 1,
                       status = CASE WHEN booked_slots + 1 >= max_slots THEN 'FULL' ELSE 'OPEN' END
                 WHERE id = :id AND version = :version AND status = 'OPEN' AND booked_slots < max_slots
                RETURNING %s
                """.formatted(ShiftRepository.COLUMNS))
                .param("id", shiftId)
                .param("version", expectedVersion)
                .query(ShiftRepository::map)
                .optional();
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
