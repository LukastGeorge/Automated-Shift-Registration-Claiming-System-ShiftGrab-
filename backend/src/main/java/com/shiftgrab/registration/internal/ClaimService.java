package com.shiftgrab.registration.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.registration.ShiftClaimed;
import com.shiftgrab.registration.internal.IsoWeeks.Segment;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shared.lock.ShiftLockProvider;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftSlots;
import com.shiftgrab.shift.ShiftStatus;

/**
 * SG-30, the core of the system (design §4, ADR-0005). Three layers of defence against overbooking:
 * <ol>
 * <li>distributed lock {@code user → shift}, so the multi-row BR-04 check reads a consistent state;</li>
 * <li>conditional {@code UPDATE ... WHERE booked_slots < max_slots}, correct even if the lock expired;</li>
 * <li>database constraints on insert (BR-01, BR-02, BR-03), correct even if the code above had a bug.</li>
 * </ol>
 * The lock wraps the transaction, so it is released only after commit: the next claimant sees committed data.
 */
@Service
@EnableConfigurationProperties(RegistrationRules.class)
public class ClaimService {

    private final ShiftSlots shifts;
    private final RegistrationRepository registrations;
    private final CurrentUserService currentUser;
    private final ShiftLockProvider locks;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final RegistrationRules rules;
    private final Clock clock;
    private final ZoneId zone;

    ClaimService(ShiftSlots shifts, RegistrationRepository registrations, CurrentUserService currentUser,
            ShiftLockProvider locks, TransactionTemplate transaction, ApplicationEventPublisher events,
            RegistrationRules rules, Clock clock, ZoneId organisationZone) {
        this.shifts = shifts;
        this.registrations = registrations;
        this.currentUser = currentUser;
        this.locks = locks;
        this.transaction = transaction;
        this.events = events;
        this.rules = rules;
        this.clock = clock;
        this.zone = organisationZone;
    }

    public Registration claim(long shiftId) {
        UserAccount staff = currentUser.currentUser();
        return locks.executeWithLocks(ShiftLockProvider.claimKeys(staff.id(), shiftId),
                () -> transaction.execute(tx -> claimLocked(staff, shiftId)));
    }

    private Registration claimLocked(UserAccount staff, long shiftId) {
        Instant now = clock.instant();
        Shift shift = shifts.findById(shiftId).orElseThrow(() -> new BusinessException(ErrorCode.SHIFT_NOT_FOUND,
                "Shift " + shiftId + " does not exist."));

        // Checks ordered from "the shift cannot be claimed by anyone" to "you cannot claim it"
        if (shift.status() == ShiftStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.SHIFT_CANCELLED, "This shift has been cancelled.");
        }
        if (shift.hasStarted(now)) {
            throw new BusinessException(ErrorCode.SHIFT_ALREADY_STARTED, "This shift has already started.");
        }
        if (now.isBefore(shift.registrationOpenAt())) {
            throw new BusinessException(ErrorCode.REGISTRATION_NOT_OPEN, "Registration has not opened yet.",
                    Map.of("registrationOpenAt", shift.registrationOpenAt().toString()));
        }
        if (registrations.holdsShift(staff.id(), shiftId)) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED, "You already hold this shift.");
        }
        if (shift.status() == ShiftStatus.FULL) {
            throw shiftFull();
        }
        if (registrations.overlapsConfirmed(staff.id(), shift.startAt(), shift.endAt())) {
            throw new BusinessException(ErrorCode.SHIFT_OVERLAP, "You already hold a shift at this time.");
        }
        checkWeeklyLimit(staff.id(), shift);

        if (!shifts.tryReserveSlot(shiftId)) {
            throw shiftFull(); // lost the race for the last slot (layer 2)
        }
        Registration registration = registrations.insert(shiftId, staff.id(), shift.startAt(), shift.endAt(),
                staff.hourlyRate(), now); // BR-10: rate snapshot
        events.publishEvent(new ShiftClaimed(registration.id(), shiftId, staff.id()));
        return registration;
    }

    /** BR-04, per ISO week the shift overlaps (a shift crossing Sunday midnight counts in both weeks). */
    private void checkWeeklyLimit(long userId, Shift shift) {
        for (Segment segment : IsoWeeks.split(shift.startAt(), shift.endAt(), zone)) {
            Duration booked = registrations.confirmedTimeWithin(userId, segment.week().start(), segment.week().end());
            Duration total = booked.plus(segment.duration());
            if (total.compareTo(rules.weeklyHourLimit()) > 0) {
                throw new BusinessException(ErrorCode.WEEKLY_LIMIT_EXCEEDED,
                        "This shift would bring you to " + hours(total) + " h in the week of "
                                + segment.week().start().atZone(zone).toLocalDate() + "; the limit is "
                                + hours(rules.weeklyHourLimit()) + " h.",
                        Map.of("weekStart", segment.week().start().atZone(zone).toLocalDate().toString(),
                                "bookedHours", hours(booked),
                                "limitHours", hours(rules.weeklyHourLimit())));
            }
        }
    }

    private static double hours(Duration d) {
        return d.toMinutes() / 60.0;
    }

    private static BusinessException shiftFull() {
        return new BusinessException(ErrorCode.SHIFT_FULL, "All slots of this shift are taken.");
    }
}
