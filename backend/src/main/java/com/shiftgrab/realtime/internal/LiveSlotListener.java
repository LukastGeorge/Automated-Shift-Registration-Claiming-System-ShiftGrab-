package com.shiftgrab.realtime.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.shiftgrab.registration.RegistrationCancelled;
import com.shiftgrab.registration.ShiftClaimed;
import com.shiftgrab.registration.ShiftRegistrationsCancelled;
import com.shiftgrab.shift.ShiftChanged;
import com.shiftgrab.shift.ShiftCreated;
import com.shiftgrab.shift.ShiftsGenerated;

/**
 * SG-40: after every committed change to a shift's slots or status, push its current state to all browsers.
 * After commit, so a rolled-back claim is never shown; asynchronous, so the claim response is not delayed.
 */
@Component
@ConditionalOnProperty(name = "shiftgrab.realtime.enabled", havingValue = "true", matchIfMissing = true)
class LiveSlotListener {

    private final SlotReader reader;
    private final SlotUpdateRelay relay;

    LiveSlotListener(SlotReader reader, SlotUpdateRelay relay) {
        this.reader = reader;
        this.relay = relay;
    }

    @ApplicationModuleListener
    void on(ShiftClaimed event) {
        push(event.shiftId());
    }

    @ApplicationModuleListener
    void on(RegistrationCancelled event) {
        push(event.shiftId());
    }

    /** Published for every cancelled shift (with or without staff), so it also covers the status change. */
    @ApplicationModuleListener
    void on(ShiftRegistrationsCancelled event) {
        push(event.shiftId());
    }

    @ApplicationModuleListener
    void on(ShiftChanged event) {
        push(event.shiftId());
    }

    @ApplicationModuleListener
    void on(ShiftCreated event) {
        push(event.shiftId());
    }

    @ApplicationModuleListener
    void on(ShiftsGenerated event) {
        event.shiftIds().forEach(this::push);
    }

    private void push(long shiftId) {
        reader.read(shiftId).ifPresent(relay::publish);
    }
}
