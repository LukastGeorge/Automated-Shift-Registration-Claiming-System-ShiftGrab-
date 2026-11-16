package com.shiftgrab.shift.internal;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftCreated;
import com.shiftgrab.shift.internal.ShiftRepository.NewShift;

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

    /** Managers see every shift; staff get 404 for shifts they cannot act on, so they cannot probe ids. */
    @Transactional(readOnly = true)
    public Shift get(long id) {
        Shift shift = shifts.findById(id).orElseThrow(() -> notFound(id));
        if (currentUser.currentRole() != Role.MANAGER && !shift.isVisibleToStaff(clock.instant())) {
            throw notFound(id);
        }
        return shift;
    }

    static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.SHIFT_NOT_FOUND, "Shift " + id + " does not exist.");
    }
}
