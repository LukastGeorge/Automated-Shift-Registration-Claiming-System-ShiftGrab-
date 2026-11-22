package com.shiftgrab.shared.lock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Redisson implementation (ADR-0004). Each key is locked with the remaining share of one overall wait budget,
 * so N keys never wait longer than {@code waitTime} in total. Leases are explicit (no watchdog): if this
 * instance dies mid-claim, Redis frees the locks after {@code leaseTime}.
 *
 * <p>Fail closed (Sprint 3 decision): when Redis cannot be reached the claim is refused with LOCK_UNAVAILABLE
 * instead of running the weekly-hours check without mutual exclusion.
 */
class RedisLockProvider implements ShiftLockProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisLockProvider.class);
    static final String KEY_PREFIX = "shiftgrab:lock:";

    private final RedissonClient redisson;
    private final LockProperties properties;
    private final MeterRegistry meters;

    RedisLockProvider(RedissonClient redisson, LockProperties properties, MeterRegistry meters) {
        this.redisson = redisson;
        this.properties = properties;
        this.meters = meters;
    }

    @Override
    public <T> T executeWithLocks(List<String> keys, Supplier<T> task) {
        Deque<RLock> held = new ArrayDeque<>();
        long start = System.nanoTime();
        long deadline = start + properties.waitTime().toNanos();
        try {
            for (String key : keys) {
                RLock lock = redisson.getLock(KEY_PREFIX + key);
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (!lock.tryLock(remaining, properties.leaseTime().toNanos(), TimeUnit.NANOSECONDS)) {
                    record(start, "timeout");
                    throw new BusinessException(ErrorCode.CLAIM_BUSY,
                            "Many people are claiming right now. Please retry in a moment.");
                }
                held.push(lock);
            }
            record(start, "acquired");
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CLAIM_BUSY, "The request was interrupted while waiting.");
        } catch (org.redisson.client.RedisException e) {
            record(start, "error");
            log.error("Lock service unavailable, refusing claim (fail closed)", e);
            throw new BusinessException(ErrorCode.LOCK_UNAVAILABLE,
                    "Claiming is temporarily unavailable. Please retry shortly.");
        } finally {
            releaseAll(held);
        }
    }

    private static void releaseAll(Deque<RLock> held) {
        while (!held.isEmpty()) {
            RLock lock = held.pop();
            try {
                lock.unlock();
            } catch (RuntimeException e) {
                // Lease already expired (task outlived leaseTime) or Redis is down: the lock frees itself.
                log.warn("Could not release lock {}: {}", lock.getName(), e.getMessage());
            }
        }
    }

    private void record(long startNanos, String result) {
        Timer.builder("shiftgrab.lock.wait")
                .description("Time spent acquiring claim locks")
                .tag("result", result)
                .register(meters)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
