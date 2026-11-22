package com.shiftgrab.shift.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shared.error.ValidationErrors;
import com.shiftgrab.shared.web.PageResponse;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftCancelled;
import com.shiftgrab.shift.ShiftChanged;
import com.shiftgrab.shift.ShiftCreated;
import com.shiftgrab.shift.ShiftStatus;
import com.shiftgrab.shift.internal.ShiftRepository.NewShift;
import com.shiftgrab.shift.internal.ShiftRepository.ShiftQuery;

/** Manager use cases on shifts (SG-20..SG-22) and visibility rules for reading them. */
@Service
public class ShiftManagementService {

    private final ShiftRepository shifts;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ShiftManagementService(ShiftRepository shifts, CurrentUserService currentUser, ApplicationEventPublisher events,
            Clock clock) {
        this.shifts = shifts;
        this.currentUser = currentUser;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Shift create(String name, Instant startAt, Instant endAt, Instant registrationOpenAt, int maxSlots) {
        ShiftRules.checkTimes(startAt, endAt, registrationOpenAt, clock.instant()).throwIfAny();
        UserAccount manager = currentUser.currentUser();
        Shift shift = shifts.insert(new NewShift(name.strip(), startAt, endAt, registrationOpenAt, maxSlots, null),
                manager.id());
        events.publishEvent(new ShiftCreated(shift.id(), manager.id()));
        return shift;
    }

    /**
     * SG-21. Fields left null are unchanged. Rules are checked against the loaded shift for precise error codes,
     * then re-enforced atomically by {@link ShiftRepository#update}; losing a race there means someone else
     * changed the shift in between, hence CONCURRENT_MODIFICATION.
     */
    @Transactional
    public Shift update(long id, long expectedVersion, ShiftChanges changes) {
        Shift current = shifts.findById(id).orElseThrow(() -> notFound(id));
        Instant now = clock.instant();
        if (current.status() == ShiftStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.SHIFT_CANCELLED, "A cancelled shift cannot be edited.");
        }
        if (current.hasStarted(now)) {
            throw new BusinessException(ErrorCode.SHIFT_ALREADY_STARTED, "A shift that has started cannot be edited.");
        }
        if (current.version() != expectedVersion) {
            throw concurrentModification();
        }

        Shift edited = changes.applyTo(current);
        boolean timesChanged = !edited.startAt().equals(current.startAt()) || !edited.endAt().equals(current.endAt());
        boolean openChanged = !edited.registrationOpenAt().equals(current.registrationOpenAt());
        if (timesChanged || openChanged) {
            ShiftRules.checkTimes(edited.startAt(), edited.endAt(), edited.registrationOpenAt(), now).throwIfAny();
        }
        if (timesChanged && current.bookedSlots() > 0) {
            throw new BusinessException(ErrorCode.SHIFT_HAS_REGISTRATIONS,
                    "Start and end cannot change once staff have registered; cancel and recreate the shift (BR-09).",
                    Map.of("bookedSlots", current.bookedSlots()));
        }
        if (edited.maxSlots() < current.bookedSlots()) {
            throw new BusinessException(ErrorCode.SHIFT_HAS_REGISTRATIONS,
                    "maxSlots cannot be lower than the " + current.bookedSlots() + " confirmed registrations (BR-08).",
                    Map.of("bookedSlots", current.bookedSlots()));
        }
        if (edited.equals(current)) {
            return current; // nothing to change: no new version, no event
        }

        Shift updated = shifts.update(edited, expectedVersion, timesChanged).orElseThrow(this::concurrentModification);
        events.publishEvent(new ShiftChanged(id, currentUser.currentUser().id(), timesChanged, current, updated));
        return updated;
    }

    /**
     * SG-22 / BR-07. Idempotent: cancelling a cancelled shift returns it unchanged without new events. A shift
     * that has started is part of the record (hours, pay) and cannot be cancelled.
     */
    @Transactional
    public Shift cancel(long id, String reason) {
        Shift current = shifts.findById(id).orElseThrow(() -> notFound(id));
        if (current.status() == ShiftStatus.CANCELLED) {
            return current;
        }
        if (current.hasStarted(clock.instant())) {
            throw new BusinessException(ErrorCode.SHIFT_ALREADY_STARTED,
                    "A shift that has started cannot be cancelled.");
        }
        return shifts.cancel(id)
                .map(cancelled -> {
                    String why = reason == null || reason.isBlank() ? null : reason.strip();
                    events.publishEvent(new ShiftCancelled(id, currentUser.currentUser().id(), why));
                    return cancelled;
                })
                // A concurrent request cancelled it first: same outcome for the caller.
                .orElseGet(() -> shifts.findById(id).orElseThrow(() -> notFound(id)));
    }

    /** Partial update of a shift; {@code null} means "keep the current value". */
    public record ShiftChanges(String name, Instant startAt, Instant endAt, Instant registrationOpenAt,
            Integer maxSlots) {

        Shift applyTo(Shift s) {
            return new Shift(s.id(), name == null ? s.name() : name.strip(), startAt == null ? s.startAt() : startAt,
                    endAt == null ? s.endAt() : endAt,
                    registrationOpenAt == null ? s.registrationOpenAt() : registrationOpenAt,
                    maxSlots == null ? s.maxSlots() : maxSlots, s.bookedSlots(), s.status(), s.templateId(),
                    s.version());
        }
    }

    private BusinessException concurrentModification() {
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                "The shift was changed by someone else. Reload and try again.");
    }

    /** Managers see every shift; staff get 404 for shifts they cannot act on, so they cannot probe ids. */
    @Transactional(readOnly = true)
    public Shift get(long id) {
        Shift shift = shifts.findById(id).orElseThrow(() -> notFound(id));
        if (currentUser.currentRole() != Role.MANAGER && !shift.isVisibleToStaff(clock.instant())) {
            throw notFound(id);
        }
        return shift;
    }

    /**
     * SG-24. Managers filter freely; for staff the filter is intersected with "OPEN or FULL and not started",
     * so a staff request for CANCELLED shifts simply returns an empty page.
     */
    @Transactional(readOnly = true)
    public PageResponse<Shift> search(Instant startsFrom, Instant startsBefore, Set<ShiftStatus> statuses, int page,
            int size) {
        new ValidationErrors()
                .rejectIf(startsFrom != null && startsBefore != null && !startsBefore.isAfter(startsFrom), "to",
                        "must be after from")
                .throwIfAny();
        Set<ShiftStatus> wanted = statuses == null || statuses.isEmpty() ? EnumSet.allOf(ShiftStatus.class)
                : EnumSet.copyOf(statuses);
        Instant from = startsFrom;
        if (currentUser.currentRole() != Role.MANAGER) {
            wanted.retainAll(STAFF_VISIBLE);
            // "Not started" means start_at > now; the query bound is inclusive, hence the 1 ms shift.
            Instant earliest = clock.instant().plusMillis(1);
            from = from == null || from.isBefore(earliest) ? earliest : from;
        }
        if (wanted.isEmpty()) {
            return PageResponse.of(List.of(), page, size, 0);
        }
        return shifts.search(new ShiftQuery(from, startsBefore, wanted), page, size);
    }

    private static final Set<ShiftStatus> STAFF_VISIBLE = EnumSet.of(ShiftStatus.OPEN, ShiftStatus.FULL);

    static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.SHIFT_NOT_FOUND, "Shift " + id + " does not exist.");
    }
}
