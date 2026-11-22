package com.shiftgrab.notification.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.identity.Users;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftCatalog;

/**
 * SG-42: every minute, finds OPEN shifts whose registration opens within the lead time (30 min) and reminds the
 * staff who opted in. "Exactly once with two instances" comes from three layers:
 * <ol>
 * <li>ShedLock: the job runs on one instance at a time;
 * <li>{@code shift_opening_reminders}: a shift is marked with {@code INSERT ... ON CONFLICT DO NOTHING}; only the
 * run that inserted the row publishes reminders, so overlapping runs, retries and restarts cannot repeat them;
 * <li>the outbox (ADR-0007): the marker and the reminder events commit together, so a crash cannot leave a marked
 * shift without its reminders, and a failed e-mail is retried for that recipient.
 * </ol>
 */
@Component
class OpeningReminderJob {

    private static final Logger log = LoggerFactory.getLogger(OpeningReminderJob.class);

    private final ShiftCatalog shifts;
    private final Users users;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration leadTime;
    private final TransactionTemplate transaction;

    OpeningReminderJob(ShiftCatalog shifts, Users users, JdbcClient jdbc, ApplicationEventPublisher events, Clock clock,
            @Value("${shiftgrab.reminders.lead-time:30m}") Duration leadTime, PlatformTransactionManager txManager) {
        this.shifts = shifts;
        this.users = users;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
        this.leadTime = leadTime;
        this.transaction = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shiftgrab.reminders.interval:1m}", initialDelayString = "30s")
    @SchedulerLock(name = "opening-reminders", lockAtMostFor = "PT5M")
    public void run() {
        remindShiftsOpeningSoon();
    }

    /**
     * A {@link TransactionTemplate} rather than {@code @Transactional}: {@link #run()} calls this method on
     * {@code this}, which bypasses Spring's proxy, so the annotation would silently do nothing. Without a
     * transaction the outbox could not store the reminder events.
     *
     * @return the number of shifts newly reminded in this run
     */
    int remindShiftsOpeningSoon() {
        Integer reminded = transaction.execute(status -> markAndPublish());
        return reminded == null ? 0 : reminded;
    }

    private int markAndPublish() {
        Instant now = clock.instant();
        List<Long> newlyMarked = shifts.findOpeningBetween(now, now.plus(leadTime)).stream()
                .map(Shift::id)
                .filter(id -> mark(id, now))
                .toList();
        if (newlyMarked.isEmpty()) {
            return 0;
        }
        List<UserAccount> recipients = users.findReminderRecipients();
        recipients.forEach(user -> events.publishEvent(new OpeningReminderDue(user.id(), newlyMarked)));
        log.info("Opening reminders for shifts {} to {} staff", newlyMarked, recipients.size());
        return newlyMarked.size();
    }

    private boolean mark(long shiftId, Instant now) {
        return jdbc.sql("""
                INSERT INTO shift_opening_reminders (shift_id, triggered_at) VALUES (:shiftId, :now)
                ON CONFLICT (shift_id) DO NOTHING
                """)
                .param("shiftId", shiftId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
