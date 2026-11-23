package com.shiftgrab.shared.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;

/** Same contract as the Redis provider, inside one JVM. */
class InMemoryShiftLockProviderTests {

    private final ShiftLockProvider locks = new InMemoryShiftLockProvider(
            new LockProperties("in-memory", null, Duration.ofMillis(200), null));

    @Test
    void runsTaskAndReleases() {
        assertThat(locks.executeWithLocks(List.of("user:1", "shift:2"), () -> "done")).isEqualTo("done");
        assertThat(locks.executeWithLocks(List.of("user:1", "shift:2"), () -> "again")).isEqualTo("again");
    }

    @Test
    void timesOutWhileAnotherThreadHoldsTheKey() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holder = pool.submit(() -> locks.executeWithLocks(List.of("shift:1"), () -> {
            held.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        held.await(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> locks.executeWithLocks(List.of("user:9", "shift:1"), () -> "never"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLAIM_BUSY));
        // user:9 was acquired before shift:1 timed out and must have been released
        assertThat(locks.executeWithLocks(List.of("user:9"), () -> "free")).isEqualTo("free");

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }
}
