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
     * ADR-0005 layer 2: {@code booked_slots + 1} only if a slot is free, the shift is OPEN and it is still the
     * version the caller checked its rules against, all in one statement. Moves the shift to FULL when the last
     * slot goes. The version is matched but not bumped (claims are not edits).
     *
     * <p>SG-81: matching the version closes a race where a manager changed the shift's times between the caller's
     * read and this update, which used to book the old times. The returned row is the shift as now stored, so its
     * times are the ones to copy into the registration.
     *
     * @return the updated shift, or empty when no slot was free or the shift changed since {@code expectedVersion}
     *         (the caller re-reads to tell which)
     */
    Optional<Shift> tryReserveSlot(long shiftId, long expectedVersion);

    /** Gives one slot back (SG-31); a FULL shift becomes OPEN again (BR-11). No effect on cancelled shifts. */
    void releaseSlot(long shiftId);
}
