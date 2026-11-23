package com.shiftgrab.reporting.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.reporting.internal.HoursReport.Row;
import com.shiftgrab.reporting.internal.HoursReport.Totals;
import com.shiftgrab.shared.error.ValidationErrors;

/**
 * Builds the hours & pay report in one SQL query. The reporting module reads {@code users} and
 * {@code shift_registrations} as a read model: a report is a cross-cutting view, and one aggregate query is far
 * cheaper than asking two modules for every registration.
 */
@Service
public class HoursReportService {

    static final long MAX_DAYS = 366;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ZoneId zone;

    HoursReportService(JdbcClient jdbc, Clock clock, ZoneId organisationZone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = organisationZone;
    }

    /** {@code from} and {@code to} are calendar days in the organisation zone, both included. */
    @Transactional(readOnly = true)
    public HoursReport hours(LocalDate from, LocalDate to) {
        new ValidationErrors()
                .rejectIf(to.isBefore(from), "to", "must not be before from")
                .rejectIf(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) >= MAX_DAYS, "to",
                        "the period must not exceed 366 days")
                .throwIfAny();
        Instant now = clock.instant();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        // Minutes are summed exactly; hours and pay are rounded once per staff member, so the totals row is the
        // sum of what is shown, as an accountant expects.
        List<Row> rows = jdbc.sql("""
                SELECT u.id, u.full_name, u.email,
                       count(r.id) AS shifts,
                       coalesce(sum(extract(epoch FROM upper(r.time_range) - lower(r.time_range)) / 60), 0) AS minutes,
                       coalesce(sum(extract(epoch FROM upper(r.time_range) - lower(r.time_range)) / 60
                                    * r.hourly_rate_snapshot), 0) AS rate_minutes
                  FROM users u
                  LEFT JOIN shift_registrations r
                         ON r.user_id = u.id
                        AND r.status = 'CONFIRMED'
                        AND lower(r.time_range) >= :start AND lower(r.time_range) < :end
                        AND upper(r.time_range) <= :now
                 WHERE u.role = 'STAFF'
                 GROUP BY u.id, u.full_name, u.email
                 ORDER BY u.full_name, u.id
                """)
                .param("start", start.atOffset(ZoneOffset.UTC))
                .param("end", end.atOffset(ZoneOffset.UTC))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getInt("shifts"),
                        rs.getBigDecimal("minutes").divide(SIXTY, 2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("rate_minutes").divide(SIXTY, 0, RoundingMode.HALF_UP)))
                .list();

        Totals totals = new Totals(rows.stream().mapToInt(Row::shifts).sum(),
                rows.stream().map(Row::hours).reduce(BigDecimal.ZERO, BigDecimal::add),
                rows.stream().map(Row::pay).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new HoursReport(from, to, zone.getId(), now, rows, totals);
    }

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
}
