package com.shiftgrab.shared.lock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;

/**
 * Single-JVM implementation kept from the prototype (ADR-0004). Correct only with one backend instance; selected
 * with {@code shiftgrab.lock.provider=in-memory} for local experiments without Redis. Same contract as the Redis
 * provider: ordered acquisition, one overall wait budget, CLAIM_BUSY on timeout.
 */
class InMemoryShiftLockProvider implements ShiftLockProvider {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final LockProperties properties;

    InMemoryShiftLockProvider(LockProperties properties) {
        this.properties = properties;
    }

    @Override
    public <T> T executeWithLocks(List<String> keys, Supplier<T> task) {
        Deque<ReentrantLock> held = new ArrayDeque<>();
        long deadline = System.nanoTime() + properties.waitTime().toNanos();
        try {
            for (String key : keys) {
                ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
                if (!lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    throw new BusinessException(ErrorCode.CLAIM_BUSY,
                            "Many people are claiming right now. Please retry in a moment.");
                }
                held.push(lock);
            }
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CLAIM_BUSY, "The request was interrupted while waiting.");
        } finally {
            while (!held.isEmpty()) {
                held.pop().unlock();
            }
        }
    }
}
