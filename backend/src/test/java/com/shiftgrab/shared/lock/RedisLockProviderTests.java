package com.shiftgrab.shared.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.shiftgrab.TestcontainersConfiguration;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * SG-33 against a real Redis. "Two instances" are simulated with two independent Redisson clients, which is
 * exactly what two backend JVMs would have: separate connections, shared Redis.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisLockProviderTests {

    @Autowired
    LockProperties properties;

    private RedissonClient instanceA;
    private RedissonClient instanceB;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void connect() {
        instanceA = client(properties.redisAddress());
        instanceB = client(properties.redisAddress());
    }

    @AfterEach
    void disconnect() {
        instanceA.shutdown();
        instanceB.shutdown();
    }

    @Test
    @DisplayName("Two instances never run the guarded section at the same time")
    void mutualExclusionAcrossInstances() throws Exception {
        ShiftLockProvider a = provider(instanceA, Duration.ofSeconds(10));
        ShiftLockProvider b = provider(instanceB, Duration.ofSeconds(10));
        int[] counter = { 0 }; // deliberately not thread-safe: only the lock protects it
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ShiftLockProvider provider = i % 2 == 0 ? a : b;
            futures.add(pool.submit(() -> {
                start.await();
                return provider.executeWithLocks(ShiftLockProvider.claimKeys(7, 42), () -> {
                    int read = counter[0];
                    Thread.onSpinWait();
                    counter[0] = read + 1;
                    return null;
                });
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(counter[0]).isEqualTo(threads);
    }

    @Test
    @DisplayName("Waiting longer than waitTime gives CLAIM_BUSY and is recorded as a timeout")
    void timesOutWithClaimBusy() throws Exception {
        ShiftLockProvider holder = provider(instanceA, Duration.ofSeconds(5));
        ShiftLockProvider impatient = provider(instanceB, Duration.ofMillis(200));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holding = pool.submit(() -> holder.executeWithLocks(List.of("shift:1"), () -> {
            held.countDown();
            await(release);
            return null;
        }));
        held.await(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> impatient.executeWithLocks(List.of("shift:1"), () -> "never"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLAIM_BUSY));
        assertThat(meters.get("shiftgrab.lock.wait").tag("result", "timeout").timer().count()).isEqualTo(1);

        release.countDown();
        holding.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        // The holder released everything: the lock is free again
        assertThat(impatient.executeWithLocks(List.of("shift:1"), () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("Partial acquisition is rolled back: holding user:1 does not leak when shift:1 times out")
    void releasesEarlierLocksWhenALaterOneTimesOut() throws Exception {
        ShiftLockProvider holder = provider(instanceA, Duration.ofSeconds(5));
        ShiftLockProvider impatient = provider(instanceB, Duration.ofMillis(200));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holding = pool.submit(() -> holder.executeWithLocks(List.of("shift:1"), () -> {
            held.countDown();
            await(release);
            return null;
        }));
        held.await(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> impatient.executeWithLocks(ShiftLockProvider.claimKeys(1, 1), () -> "never"))
                .isInstanceOf(BusinessException.class);
        // user:1 must be free immediately, while shift:1 is still held by the other instance
        assertThat(impatient.executeWithLocks(List.of("user:1"), () -> "free")).isEqualTo("free");

        release.countDown();
        holding.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    @DisplayName("A holder that outlives its lease loses the lock instead of blocking everyone")
    void leaseExpiresForStuckHolder() {
        ShiftLockProvider shortLease = new RedisLockProvider(instanceA,
                new LockProperties("redis", properties.redisAddress(), Duration.ofSeconds(1), Duration.ofMillis(300)),
                meters);

        String result = shortLease.executeWithLocks(List.of("shift:9"), () -> {
            sleep(600); // longer than the lease
            return provider(instanceB, Duration.ofMillis(100)).executeWithLocks(List.of("shift:9"), () -> "taken");
        });

        assertThat(result).isEqualTo("taken"); // and releasing the expired lock did not throw
    }

    @Test
    @DisplayName("Redis unreachable: fail closed with LOCK_UNAVAILABLE")
    void failsClosedWhenRedisIsDown() {
        RedissonClient dead = client("redis://127.0.0.1:1");
        try {
            ShiftLockProvider provider = provider(dead, Duration.ofSeconds(1));
            assertThatThrownBy(() -> provider.executeWithLocks(List.of("shift:1"), () -> "never"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.LOCK_UNAVAILABLE));
            assertThat(meters.get("shiftgrab.lock.wait").tag("result", "error").timer().count()).isEqualTo(1);
        } finally {
            dead.shutdown();
        }
    }

    @Test
    @DisplayName("The task's own exception is propagated and the locks are still released")
    void propagatesTaskFailureAndReleases() {
        ShiftLockProvider provider = provider(instanceA, Duration.ofSeconds(1));

        assertThatThrownBy(() -> provider.executeWithLocks(List.of("shift:5"), () -> {
            throw new IllegalStateException("business rule failed");
        })).hasMessage("business rule failed");
        assertThat(provider(instanceB, Duration.ofMillis(100)).executeWithLocks(List.of("shift:5"), () -> "free"))
                .isEqualTo("free");
    }

    private ShiftLockProvider provider(RedissonClient client, Duration waitTime) {
        return new RedisLockProvider(client,
                new LockProperties("redis", properties.redisAddress(), waitTime, Duration.ofSeconds(10)), meters);
    }

    private static RedissonClient client(String address) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer().setAddress(address).setConnectTimeout(500).setTimeout(500).setRetryAttempts(0);
        return Redisson.create(config);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
