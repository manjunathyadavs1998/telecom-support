package com.telecom.support.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.common.web.ClientIp;
import com.telecom.support.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit events (BR-03, XC-06; 00-overview §6, 01 backend §6/§7).
 *
 * <p>{@link #record} writes a SUCCESS row in the caller's transaction (mandatory), so it commits or rolls back with the
 * change. {@link #recordIndependently} writes in its own {@code REQUIRES_NEW} transaction, so FAILURE and DENIED rows
 * survive the caller's rollback. Time comes from {@code AppClock}, the correlation id from {@link CorrelationIdFilter}.
 * Never pass secrets, passwords, OTPs or card data in {@code details}.
 */
@Service
public class AuditService {

    /** Used when no HTTP request is bound (e.g. scheduled jobs); the column is NOT NULL. */
    static final String NO_CLIENT_IP = "unknown";
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** SUCCESS row in the caller's transaction; fails if there is none. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, Long actorUserId, String actorUsername,
                       String entityType, String entityId, Map<String, ?> details) {
        repository.insert(event(action, Outcome.SUCCESS, actorUserId, actorUsername, entityType, entityId, details));
    }

    /** Row in a new transaction that survives the caller's rollback (FAILURE / DENIED rows, §7). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String action, Outcome outcome, Long actorUserId, String actorUsername,
                                    String entityType, String entityId, Map<String, ?> details) {
        repository.insert(event(action, outcome, actorUserId, actorUsername, entityType, entityId, details));
    }

    private AuditEvent event(String action, Outcome outcome, Long actorUserId, String actorUsername,
                             String entityType, String entityId, Map<String, ?> details) {
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Audit action must be UPPER_SNAKE: " + action);
        }
        if (outcome == null) {
            throw new IllegalArgumentException("Audit outcome is required");
        }
        return new AuditEvent(clock.instant(), actorUserId, actorUsername, action, entityType, entityId, outcome,
                correlationId(), clientIp(), toJson(details));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serialisable", e);
        }
    }

    private static String correlationId() {
        String id = CorrelationIdFilter.current();
        return id != null ? id : UUID.randomUUID().toString();
    }

    /** Trusted-proxy aware client IP of the current request (01 backend §4.4). */
    private static String clientIp() {
        return ClientIp.current().orElse(NO_CLIENT_IP);
    }
}
