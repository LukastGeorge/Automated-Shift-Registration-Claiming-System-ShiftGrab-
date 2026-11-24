package com.shiftgrab.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.shiftgrab.ApiTestSupport;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftSlots;

/**
 * SG-81: races that only a precise interleaving shows. A decorator around the real {@link ShiftSlots} runs the
 * "other side" (a manager edit) in its own thread and transaction at exactly the moment between the claim's rule
 * checks and its slot UPDATE, so the race happens on every run instead of once in a thousand.
 */
@TestPropertySource(properties = "shiftgrab.claims.lock-timeout=300ms")
class ClaimRaceTests extends ApiTestSupport {

    /** Delegates everything to the real bean; {@code beforeReserve} gets the 0-based attempt number. */
    static class HookedShiftSlots implements ShiftSlots {
        private final ShiftSlots real;
        final AtomicInteger reserveCalls = new AtomicInteger();
        volatile IntConsumer beforeReserve = attempt -> { };

        HookedShiftSlots(ShiftSlots real) {
            this.real = real;
        }

        @Override
        public Optional<Shift> findById(long shiftId) {
            return real.findById(shiftId);
        }

        @Override
        public Map<Long, Shift> findAllById(Collection<Long> shiftIds) {
            return real.findAllById(shiftIds);
        }

        @Override
        public Optional<Shift> tryReserveSlot(long shiftId, long expectedVersion) {
            beforeReserve.accept(reserveCalls.getAndIncrement());
            return real.tryReserveSlot(shiftId, expectedVersion);
        }

        @Override
        public void releaseSlot(long shiftId) {
            real.releaseSlot(shiftId);
        }
    }

    @TestConfiguration
    static class Hook {
        @Bean
        @Primary
        HookedShiftSlots hookedShiftSlots(@Qualifier("shiftSlotsAdapter") ShiftSlots real) {
            return new HookedShiftSlots(real);
        }
    }

    @Autowired
    HookedShiftSlots hooked;

    @Autowired
    TransactionTemplate transaction;

    private long shiftId;

    @AfterEach
    void unhook() {
        hooked.beforeReserve = attempt -> { };
        hooked.reserveCalls.set(0);
    }

    private void openShift() {
        shiftId = insertOpenShift("Race", "2030-05-06T01:00:00Z", "2030-05-06T05:00:00Z", 3);
    }

    /** What a manager's time edit does to the row: new times, version + 1 (ShiftRepository.update). */
    private void managerMovesShiftBy(int hours) {
        CompletableFuture.runAsync(() -> jdbc.update("""
                UPDATE shifts SET start_at = start_at + make_interval(hours => ?),
                                  end_at = end_at + make_interval(hours => ?), version = version + 1
                 WHERE id = ?
                """, hours, hours, shiftId)).join(); // own thread = own connection and transaction, committed
    }

    private int bookedSlots() {
        return jdbc.queryForObject("SELECT booked_slots FROM shifts WHERE id = ?", Integer.class, shiftId);
    }

    @Test
    @DisplayName("Times changed between the checks and the UPDATE: re-checked, booked with the NEW times")
    void bookingUsesTheTimesAsStoredNow() throws Exception {
        openShift();
        hooked.beforeReserve = attempt -> {
            if (attempt == 0) {
                managerMovesShiftBy(2); // the race: after our read, before our UPDATE
            }
        };

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isCreated());

        assertThat(hooked.reserveCalls.get()).isEqualTo(2); // refused by the version check, then succeeded
        assertThat(jdbc.queryForObject("""
                SELECT lower(time_range) = '2030-05-06T03:00:00Z'::timestamptz
                   AND upper(time_range) = '2030-05-06T07:00:00Z'::timestamptz
                  FROM shift_registrations WHERE shift_id = ?
                """, Boolean.class, shiftId)).isTrue();
        assertThat(bookedSlots()).isEqualTo(1);
    }

    @Test
    @DisplayName("Changed again on the retry: 409 SHIFT_CHANGED, nothing booked")
    void keepsChangingGivesShiftChanged() throws Exception {
        openShift();
        hooked.beforeReserve = attempt -> managerMovesShiftBy(1);

        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_CHANGED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_registrations", Integer.class)).isZero();
        assertThat(bookedSlots()).isZero();
    }

    @Test
    @DisplayName("Shift row held longer than lock_timeout: fast 503 CLAIM_BUSY with Retry-After, not a hung request")
    void lockTimeoutGivesClaimBusy() throws Exception {
        openShift();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM shifts WHERE id = ? FOR UPDATE", Long.class, shiftId);
            rowLocked.countDown();
            await(release);
        }));
        assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CLAIM_BUSY"))
                .andExpect(header().string("Retry-After", "1"));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertThat(waited).isLessThan(Duration.ofSeconds(2)); // 300 ms lock_timeout plus overhead
        assertThat(bookedSlots()).isZero();

        // The timeout was SET LOCAL: once the row is free, the same claim goes through normally
        mvc.perform(post("/api/v1/shifts/{id}/registrations", shiftId).with(staff()))
                .andExpect(status().isCreated());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
