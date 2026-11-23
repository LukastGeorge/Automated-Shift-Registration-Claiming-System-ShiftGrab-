package com.shiftgrab.registration.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.shared.web.PageResponse;

@Repository
class RegistrationRepository {

    static final String COLUMNS = "r.id, r.shift_id, r.user_id, r.status, lower(r.time_range) AS start_at, "
            + "upper(r.time_range) AS end_at, r.hourly_rate_snapshot, r.registered_at, r.cancelled_at";

    private final JdbcClient jdbc;

    RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * SG-81: bounds how long this claim transaction may wait for the shift row and run, then fail fast.
     * {@code set_config(..., true)} is {@code SET LOCAL}: it ends with the transaction, so the setting never leaks
     * to the next request that borrows this pooled connection (and it stays correct behind PgBouncer in
     * transaction mode).
     */
    void limitTransactionWaits(Duration lockTimeout, Duration statementTimeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :lock, true), set_config('statement_timeout', :statement, true)")
                .param("lock", lockTimeout.toMillis() + "ms")
                .param("statement", statementTimeout.toMillis() + "ms")
                .query((rs, row) -> null)
                .list();
    }

    /** ADR-0005 layer 3 applies here: uq_registrations_active (BR-02) and ex_registrations_no_overlap (BR-03). */
    Registration insert(long shiftId, long userId, Instant startAt, Instant endAt, BigDecimal hourlyRate,
            Instant now) {
        return jdbc.sql("""
                WITH inserted AS (
                  INSERT INTO shift_registrations (shift_id, user_id, time_range, hourly_rate_snapshot, registered_at)
                  VALUES (:shiftId, :userId, tstzrange(:startAt, :endAt, '[)'), :rate, :now)
                  RETURNING *
                )
                SELECT %s FROM inserted r
                """.formatted(COLUMNS))
                .param("shiftId", shiftId)
                .param("userId", userId)
                .param("startAt", utc(startAt))
                .param("endAt", utc(endAt))
                .param("rate", hourlyRate)
                .param("now", utc(now))
                .query(RegistrationRepository::map)
                .single();
    }

    Optional<Registration> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shift_registrations r WHERE r.id = :id")
                .param("id", id)
                .query(RegistrationRepository::map)
                .optional();
    }

    /**
     * Cancels the owner's confirmed registration. The status condition makes it atomic: of two concurrent
     * cancellations only one gets the row back, so the slot is released exactly once.
     */
    Optional<Registration> cancel(long id, long userId, Instant now) {
        return jdbc.sql("""
                WITH cancelled AS (
                  UPDATE shift_registrations SET status = 'CANCELLED', cancelled_at = :now
                   WHERE id = :id AND user_id = :userId AND status = 'CONFIRMED'
                  RETURNING *
                )
                SELECT %s FROM cancelled r
                """.formatted(COLUMNS))
                .param("id", id)
                .param("userId", userId)
                .param("now", utc(now))
                .query(RegistrationRepository::map)
                .optional();
    }

    boolean holdsShift(long userId, long shiftId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM shift_registrations
                                WHERE user_id = :userId AND shift_id = :shiftId AND status = 'CONFIRMED')
                """)
                .param("userId", userId)
                .param("shiftId", shiftId)
                .query(Boolean.class)
                .single();
    }

    boolean overlapsConfirmed(long userId, Instant startAt, Instant endAt) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM shift_registrations
                                WHERE user_id = :userId AND status = 'CONFIRMED'
                                  AND time_range && tstzrange(:startAt, :endAt, '[)'))
                """)
                .param("userId", userId)
                .param("startAt", utc(startAt))
                .param("endAt", utc(endAt))
                .query(Boolean.class)
                .single();
    }

    /** Confirmed time of the user that falls inside [from, to): parts of shifts crossing the bounds are clipped. */
    Duration confirmedTimeWithin(long userId, Instant from, Instant to) {
        long seconds = jdbc.sql("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM
                         LEAST(upper(time_range), :to) - GREATEST(lower(time_range), :from))), 0)::bigint
                  FROM shift_registrations
                 WHERE user_id = :userId AND status = 'CONFIRMED'
                   AND time_range && tstzrange(:from, :to, '[)')
                """)
                .param("userId", userId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Long.class)
                .single();
        return Duration.ofSeconds(seconds);
    }

    /**
     * SG-32. Upcoming = not yet ended (includes a shift in progress), soonest first; past = ended, latest first.
     * {@code status} null means all statuses.
     */
    PageResponse<Registration> findForUser(long userId, boolean upcoming, RegistrationStatus status, Instant now,
            int page, int size) {
        String where = " WHERE r.user_id = :userId AND upper(r.time_range) " + (upcoming ? ">" : "<=") + " :now"
                + (status == null ? "" : " AND r.status = :status");
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("now", utc(now));
        if (status != null) {
            params.put("status", status.name());
        }
        long total = jdbc.sql("SELECT count(*) FROM shift_registrations r" + where).params(params)
                .query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        String order = upcoming ? "lower(r.time_range), r.id" : "lower(r.time_range) DESC, r.id DESC";
        List<Registration> content = jdbc.sql("SELECT " + COLUMNS + " FROM shift_registrations r" + where
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query(RegistrationRepository::map)
                .list();
        return PageResponse.of(content, page, size, total);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Registration map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime cancelledAt = rs.getObject("cancelled_at", OffsetDateTime.class);
        return new Registration(
                rs.getLong("id"),
                rs.getLong("shift_id"),
                rs.getLong("user_id"),
                RegistrationStatus.valueOf(rs.getString("status")),
                rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                rs.getObject("end_at", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("hourly_rate_snapshot"),
                rs.getObject("registered_at", OffsetDateTime.class).toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant());
    }
}
