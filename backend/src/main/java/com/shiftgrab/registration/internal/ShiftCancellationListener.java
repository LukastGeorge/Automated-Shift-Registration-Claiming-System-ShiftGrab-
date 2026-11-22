package com.shiftgrab.registration.internal;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.registration.ShiftRegistrationsCancelled;
import com.shiftgrab.shift.ShiftCancelled;

/**
 * BR-07: cancelling a shift cancels its confirmed registrations. A plain {@link EventListener} runs synchronously
 * in the publisher's transaction (unlike an after-commit listener), so a failure here rolls the shift
 * cancellation back too. {@link Propagation#MANDATORY} makes that assumption explicit.
 */
@Component
class ShiftCancellationListener {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ShiftCancellationListener(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ShiftCancelled event) {
        List<Long> userIds = jdbc.sql("""
                UPDATE shift_registrations SET status = 'CANCELLED', cancelled_at = :now
                 WHERE shift_id = :shiftId AND status = 'CONFIRMED'
                RETURNING user_id
                """)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("shiftId", event.shiftId())
                .query(Long.class)
                .list();
        events.publishEvent(new ShiftRegistrationsCancelled(event.shiftId(), event.actorId(),
                userIds.stream().sorted().toList(), event.reason()));
    }
}
