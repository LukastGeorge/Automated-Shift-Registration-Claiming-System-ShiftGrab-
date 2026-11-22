package com.shiftgrab.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param from sender shown to staff, e.g. {@code ShiftGrab <no-reply@example.com>}
 * @param appUrl base URL of the web app, used for links in e-mails
 */
@ConfigurationProperties("shiftgrab.notification")
record NotificationProperties(String from, String appUrl) {
}
