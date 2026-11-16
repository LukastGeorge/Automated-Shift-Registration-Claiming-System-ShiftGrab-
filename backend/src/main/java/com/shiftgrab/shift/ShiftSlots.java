package com.shiftgrab.shift;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Public API of the shift module for the claim path (SG-30, SG-31). The shift module owns the {@code shifts}
 * table, so the atomic slot counters live here; the registration module calls them inside its transaction.
 */
public interface ShiftSlots {

    Optional<Shift> findById(long shiftId);

    /** Batch lookup for list views (SG-32), keyed by id; unknown ids are simply absent. */
    Map<Long, Shift> findAllById(Collection<Long> shiftIds);

    /**
     * ADR-0005 layer 2: {@code booked_slots + 1} only if a slot is free and the shift is OPEN, in one statement,
     * so it stays correct even if the distributed lock was lost. Moves the shift to FULL when the last slot goes.
     *
     * @return false if no slot was free (the caller answers SHIFT_FULL)
     */
    boolean tryReserveSlot(long shiftId);

    /** Gives one slot back (SG-31); a FULL shift becomes OPEN again (BR-11). No effect on cancelled shifts. */
    void releaseSlot(long shiftId);
}
