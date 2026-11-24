package com.telecom.support.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditEntry.Outcome;
import com.telecom.support.common.web.CorrelationIdFilter;
import com.telecom.support.security.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records audit events (XC-06, BR-03). {@link #record} joins the caller's transaction so the audit row commits
 * atomically with the change; {@link #recordIndependently} survives a rollback (failures, denials).
 * Never pass secrets, OTPs, passwords or card data in {@code details}.
 */
@Service
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entityType, Object entityId, Map<String, ?> details) {
        repository.insert(entry(action, entityType, entityId, Outcome.SUCCESS, details, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditAction action, Outcome outcome, String entityType, Object entityId,
                                    Map<String, ?> details, String actorUsernameOverride) {
        repository.insert(entry(action, entityType, entityId, outcome, details, actorUsernameOverride));
    }

    private AuditEntry entry(AuditAction action, String entityType, Object entityId, Outcome outcome,
                             Map<String, ?> details, String actorUsernameOverride) {
        Long actorId = null;
        String actorName = actorUsernameOverride;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            actorId = user.id();
            actorName = user.getUsername();
        }
        return new AuditEntry(actorId, actorName, action, entityType, entityId == null ? null : String.valueOf(entityId),
                outcome, CorrelationIdFilter.current(), clientIp(), toJson(details));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details not serialisable", e);
        }
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
