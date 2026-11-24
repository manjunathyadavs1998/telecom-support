package com.telecom.support.audit;

public record AuditEntry(
        Long actorUserId,
        String actorUsername,
        AuditAction action,
        String entityType,
        String entityId,
        Outcome outcome,
        String correlationId,
        String clientIp,
        String detailsJson) {

    public enum Outcome { SUCCESS, FAILURE, DENIED }
}
