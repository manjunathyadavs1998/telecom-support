package com.telecom.support.audit;

import java.time.Instant;

/**
 * One append-only {@code audit_events} row (00-overview §6, BR-03, XC-06). {@code action} is a stable
 * {@code UPPER_SNAKE} name; {@code detailsJson} never holds secrets.
 */
public record AuditEvent(
        Instant occurredAt,
        Long actorUserId,
        String actorUsername,
        String action,
        String entityType,
        String entityId,
        Outcome outcome,
        String correlationId,
        String clientIp,
        String detailsJson) {

    public enum Outcome { SUCCESS, FAILURE, DENIED }
}
