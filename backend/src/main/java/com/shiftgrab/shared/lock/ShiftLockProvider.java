package com.shiftgrab.shared.lock;

import java.util.List;
import java.util.function.Supplier;

/**
 * Mutual exclusion for claim-path business checks across backend instances (ADR-0004, Strategy pattern).
 *
 * <p>Locks are acquired in the order given and released in reverse order. Callers must use a fixed global
 * order to avoid deadlocks; {@link #claimKeys} encodes the one used by the claim path: user, then shift.
 *
 * <p>A lock is a performance and consistency aid, not a correctness guarantee: its lease can expire. Correctness
 * against overbooking comes from the conditional update and database constraints (ADR-0005).
 *
 * @throws com.shiftgrab.shared.error.BusinessException {@code CLAIM_BUSY} when the locks could not be acquired
 *         within the wait time, {@code LOCK_UNAVAILABLE} when the lock service itself cannot be reached (fail closed)
 */
public interface ShiftLockProvider {

    <T> T executeWithLocks(List<String> keys, Supplier<T> task);

    static List<String> claimKeys(long userId, long shiftId) {
        return List.of("user:" + userId, "shift:" + shiftId);
    }
}
