package com.shiftgrab.registration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** SG-81: the memo may only say "sold out" for a bounded time, and a cancellation clears it at once. */
class SoldOutShiftsTests {

    private static SoldOutShifts memo(Duration ttl) {
        return new SoldOutShifts(new ClaimProperties(null, null, ttl));
    }

    @Test
    void unknownShiftIsNotSoldOut() {
        assertThat(memo(Duration.ofSeconds(1)).isSoldOut(1)).isFalse();
    }

    @Test
    void remembersUntilTheTtlRunsOut() throws InterruptedException {
        SoldOutShifts memo = memo(Duration.ofMillis(100));
        memo.remember(7);
        assertThat(memo.isSoldOut(7)).isTrue();
        assertThat(memo.isSoldOut(8)).isFalse();

        Thread.sleep(150);
        assertThat(memo.isSoldOut(7)).isFalse();
    }

    @Test
    void forgetClearsAtOnce() {
        SoldOutShifts memo = memo(Duration.ofMinutes(1));
        memo.remember(3);
        memo.forget(3);
        assertThat(memo.isSoldOut(3)).isFalse();
    }

    @Test
    void zeroTtlSwitchesTheMemoOff() {
        SoldOutShifts memo = memo(Duration.ZERO);
        memo.remember(5);
        assertThat(memo.isSoldOut(5)).isFalse();
    }
}
