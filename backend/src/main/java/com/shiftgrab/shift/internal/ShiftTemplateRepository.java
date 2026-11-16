package com.shiftgrab.shift.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.shift.internal.ShiftTemplate.Occurrence;

@Repository
class ShiftTemplateRepository {

    // Arrays and intervals are exchanged as text ('{1,2}', ISO-8601 'PT168H') to stay driver-agnostic.
    private static final String COLUMNS = "id, name, array_to_string(weekdays, ',') AS weekdays_csv, start_time, "
            + "end_time, max_slots, extract(epoch FROM open_before)::bigint AS open_before_seconds, valid_from, valid_to";

    private final JdbcClient jdbc;

    ShiftTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ShiftTemplate insert(ShiftTemplate t, long createdBy) {
        return jdbc.sql("""
                INSERT INTO shift_templates (name, weekdays, start_time, end_time, max_slots, open_before,
                                             valid_from, valid_to, created_by)
                VALUES (:name, CAST(:weekdays AS SMALLINT[]), :startTime, :endTime, :maxSlots,
                        CAST(:openBefore AS INTERVAL), :validFrom, :validTo, :createdBy)
                RETURNING %s
                """.formatted(COLUMNS))
                .param("name", t.name())
                .param("weekdays", t.weekdays().stream().map(d -> String.valueOf(d.getValue()))
                        .collect(Collectors.joining(",", "{", "}")))
                .param("startTime", t.startTime())
                .param("endTime", t.endTime())
                .param("maxSlots", t.maxSlots())
                .param("openBefore", t.openBefore().toString())
                .param("validFrom", t.validFrom())
                .param("validTo", t.validTo())
                .param("createdBy", createdBy)
                .query(ShiftTemplateRepository::map)
                .single();
    }

    Optional<ShiftTemplate> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shift_templates WHERE id = :id")
                .param("id", id)
                .query(ShiftTemplateRepository::map)
                .optional();
    }

    List<ShiftTemplate> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shift_templates ORDER BY id")
                .query(ShiftTemplateRepository::map)
                .list();
    }

    /**
     * Inserts the occurrence unless the template already has a shift starting then (uq_shifts_template_start).
     * @return the new shift id, or empty if it already existed
     */
    Optional<Long> insertOccurrence(ShiftTemplate t, Occurrence o, long createdBy) {
        return jdbc.sql("""
                INSERT INTO shifts (name, start_at, end_at, registration_open_at, max_slots, template_id, created_by)
                VALUES (:name, :startAt, :endAt, :openAt, :maxSlots, :templateId, :createdBy)
                ON CONFLICT (template_id, start_at) WHERE template_id IS NOT NULL DO NOTHING
                RETURNING id
                """)
                .param("name", t.name())
                .param("startAt", ShiftRepository.utc(o.startAt()))
                .param("endAt", ShiftRepository.utc(o.endAt()))
                .param("openAt", ShiftRepository.utc(o.registrationOpenAt()))
                .param("maxSlots", t.maxSlots())
                .param("templateId", t.id())
                .param("createdBy", createdBy)
                .query(Long.class)
                .optional();
    }

    private static ShiftTemplate map(ResultSet rs, int rowNum) throws SQLException {
        return new ShiftTemplate(
                rs.getLong("id"),
                rs.getString("name"),
                Arrays.stream(rs.getString("weekdays_csv").split(","))
                        .map(v -> DayOfWeek.of(Integer.parseInt(v)))
                        .collect(Collectors.toCollection(TreeSet::new)),
                rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getInt("max_slots"),
                Duration.ofSeconds(rs.getLong("open_before_seconds")),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class));
    }
}
