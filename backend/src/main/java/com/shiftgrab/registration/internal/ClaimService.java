package com.shiftgrab.registration.internal;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * <li>distributed lock on the <em>user</em>, so one person's multi-row rules (BR-02..BR-04) read a consistent
 * state across instances;</li>
 * <li>conditional {@code UPDATE ... WHERE booked_slots < max_slots AND version = ?}, which also serialises claims on
 * the same shift inside PostgreSQL;</li>
 * <li>database constraints on insert (BR-01, BR-02, BR-03), correct even if the code above had a bug.</li>
 * </ol>
 * The lock wraps the transaction, so it is released only after commit.
 *
 * <p>SG-81 (k6 flash sale, ADR-0004 revision): there is no per-shift lock any more. Queueing 100 requests per shift
 * on a distributed lock cost p95 6.1 s and 138 CLAIM_BUSY; PostgreSQL's row lock does the same job far cheaper.
 * In exchange the transaction is kept short and bounded: rule checks are reads before the hot-row UPDATE, no
 * external I/O happens inside it, and {@code lock_timeout}/{@code statement_timeout} turn a long wait into a fast
 * CLAIM_BUSY instead of a request that holds a pooled connection for seconds.
 */
@Service
@EnableConfigurationProperties({ RegistrationRules.class, ClaimProperties.class })
public class ClaimService {

    private static final String LOCK_NOT_AVAILABLE = "55P03"; // lock_timeout expired
    private static final String QUERY_CANCELED = "57014"; // statement_timeout expired
    // Two claims of the same user that both pass the user lock (only if its lease expired) can deadlock on the
    // EXCLUDE constraint; PostgreSQL aborts one. Nothing was written, so it is as retryable as a timeout.
    private static final String DEADLOCK_DETECTED = "40P01";

    private final ShiftSlots shifts;
    private final RegistrationRepository registrations;
    private final CurrentUserService currentUser;
    private final ShiftLockProvider locks;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final RegistrationRules rules;
    private final ClaimProperties claims;
    private final SoldOutShifts soldOut;
    private final Clock clock;
    private final ZoneId zone;
    private final MeterRegistry meters;

    ClaimService(ShiftSlots shifts, RegistrationRepository registrations, CurrentUserService currentUser,
            ShiftLockProvider locks, TransactionTemplate transaction, ApplicationEventPublisher events,
            RegistrationRules rules, ClaimProperties claims, SoldOutShifts soldOut, Clock clock,
            ZoneId organisationZone, MeterRegistry meters) {
        this.shifts = shifts;
        this.registrations = registrations;
        this.currentUser = currentUser;
        this.locks = locks;
        this.transaction = transaction;
        this.events = events;
        this.rules = rules;
        this.claims = claims;
        this.soldOut = soldOut;
        this.clock = clock;
        this.zone = organisationZone;
        this.meters = meters;
    }

    /**
     * Counts every outcome in {@code shiftgrab.claims{result}} (CLAIMED or the error code), so a dashboard shows a
     * flash sale as "5 claimed, 95 SHIFT_FULL" and a spike of CLAIM_BUSY or LOCK_UNAVAILABLE stands out (SG-72).
     */
    public Registration claim(long shiftId) {
        try {
            Registration registration = claimShift(shiftId);
            meters.counter("shiftgrab.claims", "result", "CLAIMED").increment();
            return registration;
        } catch (BusinessException e) {
            meters.counter("shiftgrab.claims", "result", e.code().name()).increment();
            throw e;
        }
    }

    private Registration claimShift(long shiftId) {
        UserAccount staff = currentUser.currentUser();
        rejectFullShiftWithoutLocking(staff.id(), shiftId);
        return locks.executeWithLocks(ShiftLockProvider.claimKeys(staff.id()), () -> {
            try {
                return transaction.execute(tx -> claimLocked(staff, shiftId));
            } catch (DataAccessException e) {
                if (!isClaimTimeout(e)) {
                    throw e;
                }
                // lock_timeout, statement_timeout or deadlock victim: rolled back, nothing written; retry shortly
                throw new BusinessException(ErrorCode.CLAIM_BUSY,
                        "Many people are claiming right now. Please retry in a moment.");
            }
        });
    }

    /**
     * PostgreSQL's own SQLStates, checked on the cause chain: Spring reports these as an uncategorised
     * {@code UncategorizedSQLException}, so matching on the exception type alone would miss them.
     */
    private static boolean isClaimTimeout(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && (LOCK_NOT_AVAILABLE.equals(sql.getSQLState())
                    || QUERY_CANCELED.equals(sql.getSQLState()) || DEADLOCK_DETECTED.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flash-sale fast path, before any lock or transaction: once a shift is FULL, the hundreds of late requests are
     * answered without queueing. Safe because it can only reject: a stale answer at worst refuses a slot freed a
     * moment ago, never grants one. The {@link SoldOutShifts} memo answers first, so a known sold-out shift is not
     * even re-read (SG-81). A double-click by the holder of the last slot still gets ALREADY_REGISTERED (AC6).
     */
    private void rejectFullShiftWithoutLocking(long userId, long shiftId) {
        boolean full = soldOut.isSoldOut(shiftId)
                || shifts.findById(shiftId).map(s -> s.status() == ShiftStatus.FULL).orElse(false);
        if (full) {
            soldOut.remember(shiftId);
            if (registrations.holdsShift(userId, shiftId)) {
                throw new BusinessException(ErrorCode.ALREADY_REGISTERED, "You already hold this shift.");
            }
            throw shiftFull();
        }
    }

    private Registration claimLocked(UserAccount staff, long shiftId) {
        registrations.limitTransactionWaits(claims.lockTimeout(), claims.statementTimeout());
        Shift shift = findShift(shiftId);
        // SG-81 race fix: the slot is reserved only if the shift is still the version the rules were checked
        // against. If a manager changed it in between, check the rules once more against the new version.
        for (int attempt = 0; attempt < 2; attempt++) {
            checkRules(staff, shift);
            Optional<Shift> reserved = shifts.tryReserveSlot(shiftId, shift.version());
            if (reserved.isPresent()) {
                return book(staff, reserved.get());
            }
            Shift now = findShift(shiftId);
            if (now.version() == shift.version()) {
                soldOut.remember(shiftId); // tells the others still arriving on this instance
                throw shiftFull(); // lost the race for the last slot (layer 2)
            }
            shift = now;
        }
        throw new BusinessException(ErrorCode.SHIFT_CHANGED,
                "This shift was changed while you were claiming it. Please review it and try again.");
    }

    /** Rule checks, all reads: they run before the hot-row UPDATE so they do not lengthen its row lock. */
    private void checkRules(UserAccount staff, Shift shift) {
        Instant now = clock.instant();
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
        if (registrations.holdsShift(staff.id(), shift.id())) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED, "You already hold this shift.");
        }
        if (shift.status() == ShiftStatus.FULL) {
            soldOut.remember(shift.id());
            throw shiftFull();
        }
        if (registrations.overlapsConfirmed(staff.id(), shift.startAt(), shift.endAt())) {
            throw new BusinessException(ErrorCode.SHIFT_OVERLAP, "You already hold a shift at this time.");
        }
        checkWeeklyLimit(staff.id(), shift);
    }

    /**
     * Times come from the row the UPDATE returned, i.e. the shift as stored now, never from an earlier read.
     * After this point the transaction only writes: the registration, then (via the event) the outbox and audit
     * rows that must commit with it (Sprint 5). E-mail and live updates run after commit.
     */
    private Registration book(UserAccount staff, Shift reserved) {
        Registration registration = registrations.insert(reserved.id(), staff.id(), reserved.startAt(),
                reserved.endAt(), staff.hourlyRate(), clock.instant()); // BR-10: rate snapshot
        events.publishEvent(new ShiftClaimed(registration.id(), reserved.id(), staff.id()));
        if (reserved.status() == ShiftStatus.FULL) {
            // This claim took the last slot. Remember it only after commit: a rollback would otherwise leave a
            // memo for a slot that is in fact free.
            long shiftId = reserved.id();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    soldOut.remember(shiftId);
                }
            });
        }
        return registration;
    }

    private Shift findShift(long shiftId) {
        return shifts.findById(shiftId).orElseThrow(() -> new BusinessException(ErrorCode.SHIFT_NOT_FOUND,
                "Shift " + shiftId + " does not exist."));
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
