package com.shiftgrab.registration.internal;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.shiftgrab.shift.ShiftCancelled;
import com.shiftgrab.shift.ShiftChanged;

/**
 * SG-81: per-instance memo of "this shift is sold out". In a flash sale, requests arriving after the last slot went
 * are answered from memory instead of opening a claim transaction and queueing for the shift row in PostgreSQL.
 *
 * <p>Safe in the same way as the lock-free fast path: the memo can only make a claim be <em>refused</em>, never
 * granted. Slots are granted only by the conditional UPDATE and the database constraints (ADR-0005). Staleness is
 * bounded: this instance forgets a shift as soon as a cancellation frees a slot or a manager changes or cancels the
 * shift (after commit); other instances catch up when the entry expires ({@code shiftgrab.claims.sold-out-memo}).
 */
@Component
class SoldOutShifts {

    private final ConcurrentHashMap<Long, Long> soldOutUntil = new ConcurrentHashMap<>(); // System.nanoTime()
    private final long ttlNanos;

    SoldOutShifts(ClaimProperties properties) {
        this.ttlNanos = properties.soldOutMemo().toNanos();
    }

    boolean isSoldOut(long shiftId) {
        Long until = soldOutUntil.get(shiftId);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() - until < 0) {
            return true;
        }
        soldOutUntil.remove(shiftId, until);
        return false;
    }

    void remember(long shiftId) {
        if (ttlNanos <= 0) {
            return; // memo switched off
        }
        long now = System.nanoTime();
        if (soldOutUntil.size() > 10_000) { // never grows unbounded: drop what has expired
            soldOutUntil.values().removeIf(until -> now - until >= 0);
        }
        soldOutUntil.put(shiftId, now + ttlNanos);
    }

    void forget(long shiftId) {
        soldOutUntil.remove(shiftId);
    }

    /** A manager edit may add slots (maxSlots up); after commit so a racing read cannot re-remember stale data. */
    @TransactionalEventListener
    void on(ShiftChanged event) {
        forget(event.shiftId());
    }

    /** A cancelled shift must answer SHIFT_CANCELLED, not SHIFT_FULL from the memo. */
    @TransactionalEventListener
    void on(ShiftCancelled event) {
        forget(event.shiftId());
    }
}
