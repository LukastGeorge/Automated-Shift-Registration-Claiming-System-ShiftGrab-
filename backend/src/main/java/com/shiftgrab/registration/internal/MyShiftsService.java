package com.shiftgrab.registration.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.registration.internal.IsoWeeks.Week;
import com.shiftgrab.shared.web.PageResponse;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftSlots;

/** SG-32: my registrations plus where I stand against the weekly limit (BR-04) this week. */
@Service
public class MyShiftsService {

    private final RegistrationRepository registrations;
    private final ShiftSlots shifts;
    private final CurrentUserService currentUser;
    private final RegistrationRules rules;
    private final Clock clock;
    private final ZoneId zone;

    MyShiftsService(RegistrationRepository registrations, ShiftSlots shifts, CurrentUserService currentUser,
            RegistrationRules rules, Clock clock, ZoneId organisationZone) {
        this.registrations = registrations;
        this.shifts = shifts;
        this.currentUser = currentUser;
        this.rules = rules;
        this.clock = clock;
        this.zone = organisationZone;
    }

    @Transactional(readOnly = true)
    public MyShifts find(boolean upcoming, RegistrationStatus status, int page, int size) {
        long userId = currentUser.currentUser().id();
        Instant now = clock.instant();

        Week week = IsoWeeks.weekOf(now, zone);
        Duration booked = registrations.confirmedTimeWithin(userId, week.start(), week.end());
        Duration remaining = rules.weeklyHourLimit().minus(booked);
        WeekSummary summary = new WeekSummary(week.start().atZone(zone).toLocalDate(),
                week.end().atZone(zone).toLocalDate().minusDays(1), hours(booked), hours(rules.weeklyHourLimit()),
                hours(remaining.isNegative() ? Duration.ZERO : remaining));

        PageResponse<Registration> found = registrations.findForUser(userId, upcoming, status, now, page, size);
        Map<Long, Shift> shiftsById = shifts.findAllById(found.content().stream().map(Registration::shiftId)
                .distinct().toList());
        return new MyShifts(summary, found.map(r -> item(r, shiftsById.get(r.shiftId()), now)));
    }

    private Item item(Registration r, Shift shift, Instant now) {
        Instant cancelDeadline = r.startAt().minus(rules.cancelDeadline());
        boolean cancellable = r.status() == RegistrationStatus.CONFIRMED && !now.isAfter(cancelDeadline);
        return new Item(r.id(), r.shiftId(), shift == null ? null : shift.name(), r.status(), r.startAt(), r.endAt(),
                hours(r.duration()), r.hourlyRateSnapshot(), r.registeredAt(), r.cancelledAt(), cancellable,
                cancelDeadline);
    }

    private static BigDecimal hours(Duration d) {
        return BigDecimal.valueOf(d.toMinutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    public record MyShifts(WeekSummary currentWeek, PageResponse<Item> registrations) {
    }

    /** ISO week in the organisation zone, Monday to Sunday inclusive. */
    public record WeekSummary(LocalDate weekStart, LocalDate weekEnd, BigDecimal confirmedHours,
            BigDecimal limitHours, BigDecimal remainingHours) {
    }

    public record Item(long id, long shiftId, String shiftName, RegistrationStatus status, Instant startAt,
            Instant endAt, BigDecimal hours, BigDecimal hourlyRateSnapshot, Instant registeredAt, Instant cancelledAt,
            boolean cancellable, Instant cancelDeadline) {
    }
}
