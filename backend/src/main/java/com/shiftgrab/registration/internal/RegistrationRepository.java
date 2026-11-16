package com.shiftgrab.registration.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RegistrationRepository {

    static final String COLUMNS = "r.id, r.shift_id, r.user_id, r.status, lower(r.time_range) AS start_at, "
            + "upper(r.time_range) AS end_at, r.hourly_rate_snapshot, r.registered_at, r.cancelled_at";

    private final JdbcClient jdbc;

    RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
