package com.shiftgrab.shift.internal;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shared.error.ValidationErrors;
import com.shiftgrab.shift.ShiftTemplateCreated;
import com.shiftgrab.shift.ShiftsGenerated;
import com.shiftgrab.shift.internal.ShiftTemplate.Occurrence;

/** Recurring shifts (SG-23): define a template, then generate its shifts idempotently in one transaction. */
@Service
public class ShiftTemplateService {

    static final Duration MAX_OPEN_BEFORE = Duration.ofDays(90);
    static final long MAX_RANGE_DAYS = 366;

    private final ShiftTemplateRepository templates;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    ShiftTemplateService(ShiftTemplateRepository templates, CurrentUserService currentUser,
            ApplicationEventPublisher events, Clock clock, ZoneId organisationZone) {
        this.templates = templates;
        this.currentUser = currentUser;
        this.events = events;
        this.clock = clock;
        this.zone = organisationZone;
    }

    @Transactional
    public ShiftTemplate create(String name, Set<Integer> weekdays, LocalTime startTime, LocalTime endTime,
            int maxSlots, Duration openBefore, LocalDate validFrom, LocalDate validTo) {
        Duration length = ShiftTemplate.duration(startTime, endTime);
        new ValidationErrors()
                .rejectIf(weekdays.stream().anyMatch(d -> d == null || d < 1 || d > 7), "weekdays",
                        "values must be ISO weekdays 1 (Monday) to 7 (Sunday)")
                .rejectIf(length.compareTo(ShiftRules.MAX_DURATION) > 0, "endTime",
                        "shift must not be longer than 12 hours")
                .rejectIf(openBefore.isNegative() || openBefore.isZero() || openBefore.compareTo(MAX_OPEN_BEFORE) > 0,
                        "openBefore", "must be positive and at most 90 days")
                .rejectIf(validTo.isBefore(validFrom), "validTo", "must not be before validFrom")
                .rejectIf(ChronoUnit.DAYS.between(validFrom, validTo) >= MAX_RANGE_DAYS, "validTo",
                        "date range must not exceed 366 days")
                .throwIfAny();

        TreeSet<DayOfWeek> days = new TreeSet<>();
        weekdays.forEach(d -> days.add(DayOfWeek.of(d)));
        long actorId = currentUser.currentUser().id();
        ShiftTemplate template = templates.insert(new ShiftTemplate(0, name.strip(), days, startTime, endTime,
                maxSlots, openBefore, validFrom, validTo), actorId);
        events.publishEvent(new ShiftTemplateCreated(template.id(), actorId, template.name()));
        return template;
    }

    @Transactional(readOnly = true)
    public ShiftTemplate get(long id) {
        return templates.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND,
                "Shift template " + id + " does not exist."));
    }

    @Transactional(readOnly = true)
    public List<ShiftTemplate> list() {
        return templates.findAll();
    }

    /**
     * SG-23. All-or-nothing (AC2): one transaction, so any failure leaves no shift behind. Idempotent (AC3):
     * occurrences that already exist are skipped by the unique index, and occurrences that have already started
     * are skipped because a shift must start in the future.
     */
    @Transactional
    public GenerationResult generate(long templateId) {
        ShiftTemplate template = get(templateId);
        long actorId = currentUser.currentUser().id();
        Instant now = clock.instant();

        List<Long> created = new ArrayList<>();
        int skippedPast = 0;
        int skippedExisting = 0;
        for (Occurrence occurrence : template.occurrences(zone)) {
            if (!occurrence.startAt().isAfter(now)) {
                skippedPast++;
                continue;
            }
            Optional<Long> id = templates.insertOccurrence(template, occurrence, actorId);
            if (id.isPresent()) {
                created.add(id.get());
            } else {
                skippedExisting++;
            }
        }
        if (!created.isEmpty()) {
            events.publishEvent(new ShiftsGenerated(templateId, actorId, created));
        }
        return new GenerationResult(templateId, created, skippedExisting, skippedPast);
    }

    public record GenerationResult(long templateId, List<Long> createdShiftIds, int skippedExisting,
            int skippedPast) {
    }
}
