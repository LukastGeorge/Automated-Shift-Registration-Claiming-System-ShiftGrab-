package com.shiftgrab.shared.scheduling;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs with ShedLock: every instance runs the scheduler, but a job only executes on the instance that
 * acquires its row in the {@code shedlock} table, so with two backends each job still runs once (SG-42).
 * Times come from the database clock, so instances with slightly different clocks agree on who holds the lock.
 * Disabled with {@code shiftgrab.scheduling.enabled=false} (integration tests call the jobs directly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@ConditionalOnProperty(name = "shiftgrab.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
