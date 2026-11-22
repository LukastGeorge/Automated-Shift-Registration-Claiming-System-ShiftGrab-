package com.shiftgrab.notification.internal;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.shiftgrab.notification.internal.EmailComposer.Email;

/** Sends one plain-text e-mail. Failures propagate, so the event publication stays FAILED and is resubmitted. */
@Component
class Mailer {

    private final JavaMailSender sender;
    private final NotificationProperties properties;

    Mailer(JavaMailSender sender, NotificationProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    void send(Email email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        sender.send(message);
    }
}
