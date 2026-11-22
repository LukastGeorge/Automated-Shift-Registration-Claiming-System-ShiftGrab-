package com.shiftgrab.shared.scheduling;

import java.time.Duration;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the event publication registry (transactional outbox, SG-41) moving:
 * <ul>
 * <li>a listener that threw (e.g. the mail server was down) leaves its publication FAILED; it is resubmitted here;
 * <li>a publication whose instance died mid-way is marked FAILED by Modulith's staleness monitor, then resubmitted;
 * <li>completed publications are deleted after a week.
 * </ul>
 * Each job runs on one instance at a time (ShedLock), so a publication is never resubmitted twice in parallel.
 */
@Component
public class EventPublicationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationMaintenance.class);

    private final FailedEventPublications failed;
    private final CompletedEventPublications completed;

    EventPublicationMaintenance(FailedEventPublications failed, CompletedEventPublications completed) {
        this.failed = failed;
        this.completed = completed;
    }

    @Scheduled(fixedDelayString = "${shiftgrab.events.resubmit-interval:1m}", initialDelayString = "1m")
    @SchedulerLock(name = "event-publications-resubmit", lockAtMostFor = "PT5M")
    public void resubmitFailed() {
        resubmitFailedOlderThan(Duration.ofMinutes(1));
    }

    /** {@code minAge} leaves very recent failures alone, so a flapping mail server is not hammered. */
    public void resubmitFailedOlderThan(Duration minAge) {
        log.debug("Resubmitting failed event publications older than {}", minAge);
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(minAge).withBatchSize(100));
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${shiftgrab.time-zone}")
    @SchedulerLock(name = "event-publications-cleanup", lockAtMostFor = "PT10M")
    public void deleteCompleted() {
        completed.deletePublicationsOlderThan(Duration.ofDays(7));
    }
}
