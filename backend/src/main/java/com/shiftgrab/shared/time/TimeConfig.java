package com.shiftgrab.shared.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "Now" and the organisation time zone are injected rather than read statically, so tests can control time
 * (design §7). Instants are stored in UTC; weeks and recurring templates are computed in {@link ZoneId}.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId organisationZone(@Value("${shiftgrab.time-zone}") String zone) {
        return ZoneId.of(zone);
    }
}
