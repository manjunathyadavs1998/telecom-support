package com.telecom.support.audit;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Insert-only writer for {@code audit_events}; V2 triggers reject UPDATE and DELETE (00-overview §6). */
@Repository
public class AuditRepository {

    private static final String INSERT = """
            INSERT INTO audit_events (occurred_at, actor_user_id, actor_username, action, entity_type, entity_id,
                                      outcome, correlation_id, client_ip, details)
            VALUES (:occurredAt, :actorUserId, :actorUsername, :action, :entityType, :entityId,
                    :outcome, :correlationId, :clientIp, CAST(:details AS JSON))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEvent e) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                // stored as UTC DATETIME(6) (CLAUDE.md backend rule 8)
                .addValue("occurredAt", LocalDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC))
                .addValue("actorUserId", e.actorUserId())
                .addValue("actorUsername", e.actorUsername())
                .addValue("action", e.action())
                .addValue("entityType", e.entityType())
                .addValue("entityId", e.entityId())
                .addValue("outcome", e.outcome().name())
                .addValue("correlationId", e.correlationId())
                .addValue("clientIp", e.clientIp())
                .addValue("details", e.detailsJson()));
    }
}
