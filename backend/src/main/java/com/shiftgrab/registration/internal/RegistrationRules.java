package com.shiftgrab.registration.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment settings for the claim rules (Sprint 3 decision: configuration, not a runtime API).
 *
 * @param weeklyHourLimit BR-04, default 40 h per ISO week in the organisation time zone
 * @param cancelDeadline  BR-06, staff may cancel until {@code startAt - cancelDeadline}, default 24 h
 */
@ConfigurationProperties("shiftgrab.rules")
public record RegistrationRules(Duration weeklyHourLimit, Duration cancelDeadline) {

    public RegistrationRules {
        weeklyHourLimit = weeklyHourLimit == null ? Duration.ofHours(40) : weeklyHourLimit;
        cancelDeadline = cancelDeadline == null ? Duration.ofHours(24) : cancelDeadline;
    }
}
