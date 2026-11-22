package com.shiftgrab.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shiftgrab.audit.internal.AuditAction.Target;
import com.shiftgrab.shared.web.PageResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Writes and reads {@code audit_log}. There is deliberately no update or delete (append-only, migration V4). */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    AuditRepository(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    void record(Long actorId, AuditAction action, Target target, Long targetId, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, target_type, target_id, details, occurred_at)
                VALUES (:actorId, :action, :target, :targetId, CAST(:details AS JSONB), :at)
                """)
                .param("actorId", actorId)
                .param("action", action.name())
                .param("target", target.name())
                .param("targetId", targetId)
                .param("details", json.writeValueAsString(details))
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    public record AuditQuery(Long actorId, AuditAction action, Instant from, Instant to) {
    }

    public record AuditEntry(long id, Instant occurredAt, Long actorId, String actorName, AuditAction action,
            Target targetType, Long targetId, JsonNode details) {
    }

    /** Newest first. Every filter is optional; {@code to} is exclusive. */
    public PageResponse<AuditEntry> search(AuditQuery q, int page, int size) {
        String where = """
                 WHERE (CAST(:actorId AS BIGINT) IS NULL OR a.actor_id = :actorId)
                   AND (CAST(:action AS VARCHAR) IS NULL OR a.action = :action)
                   AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR a.occurred_at >= :from)
                   AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR a.occurred_at < :to)
                """;
        long total = bind(jdbc.sql("SELECT count(*) FROM audit_log a" + where), q).query(Long.class).single();
        // The actor's name is read from the users table at query time: a read model, not a cross-module call.
        var rows = bind(jdbc.sql("""
                SELECT a.id, a.occurred_at, a.actor_id, u.full_name, a.action, a.target_type, a.target_id, a.details
                  FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
                """ + where + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit OFFSET :offset"), q)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(this::map)
                .list();
        return PageResponse.of(rows, page, size, total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, AuditQuery q) {
        return spec.param("actorId", q.actorId())
                .param("action", q.action() == null ? null : q.action().name())
                .param("from", utc(q.from()))
                .param("to", utc(q.to()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private AuditEntry map(ResultSet rs, int row) throws SQLException {
        String details = rs.getString("details");
        return new AuditEntry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", Long.class), rs.getString("full_name"),
                AuditAction.valueOf(rs.getString("action")), Target.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", Long.class), details == null ? null : json.readTree(details));
    }
}
