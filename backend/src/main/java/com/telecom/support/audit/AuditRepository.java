package com.telecom.support.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only writer for {@code audit_events}. UPDATE/DELETE are blocked by DB triggers (V2). */
@Repository
public class AuditRepository {

    private static final String INSERT = """
            INSERT INTO audit_events (actor_user_id, actor_username, action, entity_type, entity_id, outcome,
                                      correlation_id, client_ip, details)
            VALUES (:actorUserId, :actorUsername, :action, :entityType, :entityId, :outcome,
                    :correlationId, :clientIp, CAST(:details AS JSON))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEntry e) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("actorUserId", e.actorUserId())
                .addValue("actorUsername", e.actorUsername())
                .addValue("action", e.action().name())
                .addValue("entityType", e.entityType())
                .addValue("entityId", e.entityId())
                .addValue("outcome", e.outcome().name())
                .addValue("correlationId", e.correlationId())
                .addValue("clientIp", e.clientIp())
                .addValue("details", e.detailsJson()));
    }
}
