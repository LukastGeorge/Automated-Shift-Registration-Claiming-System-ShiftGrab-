package com.shiftgrab.shift.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.shift.Shift;
import com.shiftgrab.shift.ShiftStatus;

/** Plain SQL on purpose: the claim path (Sprint 3) relies on conditional updates that must stay explicit. */
@Repository
public class ShiftRepository {

    static final String COLUMNS = "id, name, start_at, end_at, registration_open_at, max_slots, booked_slots, "
            + "status, template_id, version";

    private final JdbcClient jdbc;

    ShiftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Shift insert(NewShift shift, long createdBy) {
        return jdbc.sql("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, template_id, created_by)
                VALUES (:name, :startAt, :endAt, :openAt, :maxSlots, :templateId, :createdBy)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("name", shift.name())
                .param("startAt", utc(shift.startAt()))
                .param("endAt", utc(shift.endAt()))
                .param("openAt", utc(shift.registrationOpenAt()))
                .param("maxSlots", shift.maxSlots())
                .param("templateId", shift.templateId())
                .param("createdBy", createdBy)
                .query(ShiftRepository::map)
                .single();
    }

    public Optional<Shift> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shifts WHERE id = :id")
                .param("id", id)
                .query(ShiftRepository::map)
                .optional();
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Shift map(ResultSet rs, int rowNum) throws SQLException {
        Long templateId = rs.getObject("template_id", Long.class);
        return new Shift(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                rs.getObject("end_at", OffsetDateTime.class).toInstant(),
                rs.getObject("registration_open_at", OffsetDateTime.class).toInstant(),
                rs.getInt("max_slots"),
                rs.getInt("booked_slots"),
                ShiftStatus.valueOf(rs.getString("status")),
                templateId,
                rs.getLong("version"));
    }

    /** Data needed to insert a shift; {@code templateId} is null for one-off shifts. */
    public record NewShift(String name, Instant startAt, Instant endAt, Instant registrationOpenAt, int maxSlots,
            Long templateId) {
    }
}
