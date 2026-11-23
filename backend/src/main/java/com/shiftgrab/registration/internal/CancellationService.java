package com.shiftgrab.registration.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.registration.RegistrationCancelled;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shift.ShiftSlots;

/**
 * SG-31: staff cancel their own registration until {@code startAt - cancelDeadline} (BR-06); the slot is released
 * at once and a FULL shift reopens (BR-11).
 *
 * <p>No distributed lock on purpose: the conditional UPDATE is atomic on its own, and cancelling only ever frees
 * capacity, so it cannot break BR-01..BR-04. Staff can therefore still cancel while Redis is down.
 */
@Service
public class CancellationService {

    private final RegistrationRepository registrations;
    private final ShiftSlots shifts;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;
    private final RegistrationRules rules;
    private final Clock clock;
    private final SoldOutShifts soldOut;

    CancellationService(RegistrationRepository registrations, ShiftSlots shifts, CurrentUserService currentUser,
            ApplicationEventPublisher events, RegistrationRules rules, Clock clock, SoldOutShifts soldOut) {
        this.soldOut = soldOut;
        this.registrations = registrations;
        this.shifts = shifts;
        this.currentUser = currentUser;
        this.events = events;
        this.rules = rules;
        this.clock = clock;
    }

    /** Idempotent: an already cancelled registration (by its owner or by a shift cancellation) is a no-op. */
    @Transactional
    public void cancel(long registrationId) {
        long userId = currentUser.currentUser().id();
        Registration registration = registrations.findById(registrationId)
                .filter(r -> r.userId() == userId) // someone else's registration looks like a missing one
                .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_NOT_FOUND,
                        "Registration " + registrationId + " does not exist."));
        if (registration.status() == RegistrationStatus.CANCELLED) {
            return;
        }
        Instant now = clock.instant();
        Instant deadline = registration.startAt().minus(rules.cancelDeadline());
        if (now.isAfter(deadline)) {
            throw new BusinessException(ErrorCode.CANCEL_DEADLINE_PASSED,
                    "Registrations can only be cancelled until " + rules.cancelDeadline().toHours()
                            + " h before the shift starts.",
                    Map.of("cancelDeadline", deadline.toString()));
        }
        registrations.cancel(registrationId, userId, now).ifPresent(cancelled -> {
            shifts.releaseSlot(cancelled.shiftId());
            soldOut.forget(cancelled.shiftId()); // SG-81: the freed slot is claimable at once on this instance
            events.publishEvent(new RegistrationCancelled(cancelled.id(), cancelled.shiftId(), userId));
        });
    }
}
