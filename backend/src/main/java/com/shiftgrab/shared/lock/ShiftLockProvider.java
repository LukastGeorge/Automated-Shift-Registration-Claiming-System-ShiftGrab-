package com.shiftgrab.shared.lock;

import java.util.List;
import java.util.function.Supplier;

/**
 * Mutual exclusion for claim-path business checks across backend instances (ADR-0004, Strategy pattern).
 *
 * <p>Locks are acquired in the order given and released in reverse order. Callers must use a fixed global
 * order to avoid deadlocks. {@link #claimKeys} gives the keys of the claim path.
 *
 * <p>A lock is a performance and consistency aid, not a correctness guarantee: its lease can expire. Correctness
 * against overbooking comes from the conditional update and database constraints (ADR-0005).
 *
 * @throws com.shiftgrab.shared.error.BusinessException {@code CLAIM_BUSY} when the locks could not be acquired
 *         within the wait time, {@code LOCK_UNAVAILABLE} when the lock service itself cannot be reached (fail closed)
 */
public interface ShiftLockProvider {

    <T> T executeWithLocks(List<String> keys, Supplier<T> task);

    /**
     * The claim path locks the <em>user</em> only (SG-81, ADR-0004 revision): it guards the multi-row rules of one
     * person (BR-04 weekly hours, duplicate and overlapping claims). Slots are not locked here: the conditional
     * UPDATE serialises claims on the shift row inside PostgreSQL, which the k6 flash sale showed to be far cheaper
     * than queueing 100 requests per shift on a distributed lock (p95 6.1 s, 138 CLAIM_BUSY before the change).
     */
    static List<String> claimKeys(long userId) {
        return List.of("user:" + userId);
    }
}
