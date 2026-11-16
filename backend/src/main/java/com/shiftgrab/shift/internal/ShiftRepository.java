package com.shiftgrab.shift.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.shared.web.PageResponse;
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

    /**
     * Applies an edit only if nothing relevant changed since the caller read the shift (SG-21). Every guard is
     * in the WHERE clause, so the check and the write are one atomic step even against concurrent claims:
     * version (optimistic lock), BR-08 ({@code booked_slots <= max_slots}) and BR-09 (no time change once
     * booked). Status is recomputed from occupancy (BR-11). Empty result = a guard failed in a race.
     */
    public Optional<Shift> update(Shift edited, long expectedVersion, boolean timesChanged) {
        return jdbc.sql("""
                UPDATE shifts
                   SET name = :name, start_at = :startAt, end_at = :endAt, registration_open_at = :openAt,
                       max_slots = :maxSlots,
                       status = CASE WHEN booked_slots >= :maxSlots THEN 'FULL' ELSE 'OPEN' END,
                       version = version + 1
                 WHERE id = :id AND version = :version AND status <> 'CANCELLED'
                   AND booked_slots <= :maxSlots
                   AND (NOT CAST(:timesChanged AS BOOLEAN) OR booked_slots = 0)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", edited.id())
                .param("version", expectedVersion)
                .param("name", edited.name())
                .param("startAt", utc(edited.startAt()))
                .param("endAt", utc(edited.endAt()))
                .param("openAt", utc(edited.registrationOpenAt()))
                .param("maxSlots", edited.maxSlots())
                .param("timesChanged", timesChanged)
                .query(ShiftRepository::map)
                .optional();
    }

    /**
     * Filtered page sorted by start time (SG-24). Uses {@code idx_shifts_start_status}; {@code id} breaks ties so
     * pages are stable when several shifts start at the same instant.
     */
    public PageResponse<Shift> search(ShiftQuery query, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE status IN (:statuses)");
        Map<String, Object> params = new HashMap<>();
        params.put("statuses", query.statuses().stream().map(Enum::name).toList());
        if (query.startsFrom() != null) {
            where.append(" AND start_at >= :from");
            params.put("from", utc(query.startsFrom()));
        }
        if (query.startsBefore() != null) {
            where.append(" AND start_at < :to");
            params.put("to", utc(query.startsBefore()));
        }
        long total = jdbc.sql("SELECT count(*) FROM shifts" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Shift> content = jdbc.sql("SELECT " + COLUMNS + " FROM shifts" + where
                        + " ORDER BY start_at, id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(ShiftRepository::map)
                .list();
        return PageResponse.of(content, page, size, total);
    }

    /** Search criteria; bounds are optional, {@code statuses} must not be empty. */
    public record ShiftQuery(Instant startsFrom, Instant startsBefore, Set<ShiftStatus> statuses) {
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
