package com.shiftgrab.notification.internal;

import java.time.ZoneId;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.identity.Users;
import com.shiftgrab.notification.internal.EmailComposer.Email;
import com.shiftgrab.registration.RegistrationCancelled;
import com.shiftgrab.registration.ShiftClaimed;
import com.shiftgrab.registration.ShiftRegistrationsCancelled;
import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftSlots;

/**
 * SG-41: e-mails after a claim, a cancellation by the staff member, and a shift cancelled by the manager.
 *
 * <p>{@link ApplicationModuleListener} = after commit, asynchronous, in its own transaction. Spring Modulith first
 * stores each event in {@code event_publication} inside the business transaction (transactional outbox). If sending
 * throws, the publication stays FAILED and {@code EventPublicationMaintenance} resubmits it later, so a mail outage
 * never rolls back a claim and never loses the e-mail. Delivery is at-least-once: a resubmitted shift cancellation
 * may reach a staff member twice, which is preferable to not at all.
 */
@Component
@ConditionalOnProperty(name = "shiftgrab.notification.enabled", havingValue = "true", matchIfMissing = true)
class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final Users users;
    private final ShiftSlots shifts;
    private final Mailer mailer;
    private final EmailComposer composer;

    NotificationListener(Users users, ShiftSlots shifts, Mailer mailer, NotificationProperties properties,
            ZoneId organisationZone) {
        this.users = users;
        this.shifts = shifts;
        this.mailer = mailer;
        this.composer = new EmailComposer(organisationZone, properties.appUrl());
    }

    @ApplicationModuleListener
    void on(ShiftClaimed event) {
        send(event.userId(), event.shiftId(), composer::claimed);
    }

    @ApplicationModuleListener
    void on(RegistrationCancelled event) {
        send(event.userId(), event.shiftId(), composer::cancelledByStaff);
    }

    @ApplicationModuleListener
    void on(ShiftRegistrationsCancelled event) {
        for (long userId : event.userIds()) {
            send(userId, event.shiftId(), (user, shift) -> composer.shiftCancelled(user, shift, event.reason()));
        }
    }

    private void send(long userId, long shiftId, BiFunction<UserAccount, Shift, Email> compose) {
        UserAccount user = users.findById(userId).orElse(null);
        Shift shift = shifts.findById(shiftId).orElse(null);
        if (user == null || shift == null) {
            // Nothing to retry: the data is gone. Logged so it can still be investigated.
            log.warn("Skipping e-mail: user {} or shift {} no longer exists", userId, shiftId);
            return;
        }
        mailer.send(compose.apply(user, shift));
    }
}
