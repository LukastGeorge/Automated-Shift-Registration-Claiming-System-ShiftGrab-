package com.shiftgrab.audit.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.audit.internal.AuditAction.Target;
import com.shiftgrab.identity.HourlyRateChanged;
import com.shiftgrab.registration.RegistrationCancelled;
import com.shiftgrab.registration.ShiftClaimed;
import com.shiftgrab.registration.ShiftRegistrationsCancelled;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftCancelled;
import com.shiftgrab.shift.ShiftChanged;
import com.shiftgrab.shift.ShiftCreated;
import com.shiftgrab.shift.ShiftSlots;
import com.shiftgrab.shift.ShiftTemplateCreated;
import com.shiftgrab.shift.ShiftsGenerated;

/**
 * SG-52: records every business change in the same transaction as the change itself. A plain {@link EventListener}
 * runs synchronously inside the publisher's transaction, so a change without its audit entry (or an entry for a
 * change that was rolled back) cannot exist. {@link Propagation#MANDATORY} turns a publisher without a transaction
 * into an error instead of a silently unaudited change.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class AuditListener {

    private final AuditRepository audit;
    private final ShiftSlots shifts;

    AuditListener(AuditRepository audit, ShiftSlots shifts) {
        this.audit = audit;
        this.shifts = shifts;
    }

    @EventListener
    void on(ShiftCreated e) {
        Map<String, Object> details = shifts.findById(e.shiftId()).map(AuditListener::summary).orElseGet(Map::of);
        audit.record(e.actorId(), AuditAction.SHIFT_CREATED, Target.SHIFT, e.shiftId(), details);
    }

    @EventListener
    void on(ShiftChanged e) {
        audit.record(e.actorId(), AuditAction.SHIFT_UPDATED, Target.SHIFT, e.shiftId(), diff(e.before(), e.after()));
    }

    @EventListener
    void on(ShiftCancelled e) {
        audit.record(e.actorId(), AuditAction.SHIFT_CANCELLED, Target.SHIFT, e.shiftId(),
                details("reason", e.reason()));
    }

    @EventListener
    void on(ShiftRegistrationsCancelled e) {
        audit.record(e.actorId(), AuditAction.SHIFT_REGISTRATIONS_CANCELLED, Target.SHIFT, e.shiftId(),
                details("affectedUserIds", e.userIds(), "reason", e.reason()));
    }

    @EventListener
    void on(ShiftTemplateCreated e) {
        audit.record(e.actorId(), AuditAction.TEMPLATE_CREATED, Target.TEMPLATE, e.templateId(),
                details("name", e.name()));
    }

    @EventListener
    void on(ShiftsGenerated e) {
        audit.record(e.actorId(), AuditAction.SHIFTS_GENERATED, Target.TEMPLATE, e.templateId(),
                details("created", e.shiftIds().size(), "shiftIds", e.shiftIds()));
    }

    @EventListener
    void on(ShiftClaimed e) {
        audit.record(e.userId(), AuditAction.SHIFT_CLAIMED, Target.REGISTRATION, e.registrationId(),
                details("shiftId", e.shiftId()));
    }

    @EventListener
    void on(RegistrationCancelled e) {
        audit.record(e.userId(), AuditAction.REGISTRATION_CANCELLED, Target.REGISTRATION, e.registrationId(),
                details("shiftId", e.shiftId()));
    }

    @EventListener
    void on(HourlyRateChanged e) {
        audit.record(e.actorId(), AuditAction.HOURLY_RATE_CHANGED, Target.USER, e.userId(),
                details("hourlyRate", change(e.oldRate(), e.newRate())));
    }

    static Map<String, Object> summary(Shift s) {
        return details("name", s.name(), "startAt", s.startAt().toString(), "endAt", s.endAt().toString(),
                "registrationOpenAt", s.registrationOpenAt().toString(), "maxSlots", s.maxSlots());
    }

    /** Only the fields that changed, each as {@code {"from": ..., "to": ...}}. */
    static Map<String, Object> diff(Shift before, Shift after) {
        Map<String, Object> from = summary(before);
        Map<String, Object> to = summary(after);
        Map<String, Object> changes = new LinkedHashMap<>();
        from.forEach((field, old) -> {
            if (!Objects.equals(old, to.get(field))) {
                changes.put(field, change(old, to.get(field)));
            }
        });
        return changes;
    }

    private static Map<String, Object> change(Object from, Object to) {
        return details("from", from, "to", to);
    }

    /** Like {@code Map.of}, but keeps insertion order and allows null values (e.g. no reason given). */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
