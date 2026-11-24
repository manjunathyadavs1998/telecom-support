package com.telecom.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.common.web.CorrelationIdFilter;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * B4: audit service and append-only trigger cases (01 backend §6/§7/§8, 00-overview §6; BR-03, XC-06).
 * Login, logout and ACCESS_DENIED cases are added in B9.
 */
class AuditTrailIT extends AbstractMySqlIT {

    @Autowired
    AuditService audit;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper objectMapper;

    private String correlationId;

    @BeforeEach
    void bindRequest() {
        correlationId = UUID.randomUUID().toString();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void unbindRequest() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordWritesRowWithCorrelationIdClientIpTimeAndDetailsInCallerTransaction() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        tx.executeWithoutResult(s -> audit.record("CASE_ASSIGNED", 42L, "agent01", "CASE", "CS-1",
                Map.of("old", "A", "new", "B")));

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("action", "CASE_ASSIGNED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_user_id", 42L)
                .containsEntry("actor_username", "agent01")
                .containsEntry("entity_type", "CASE")
                .containsEntry("entity_id", "CS-1")
                .containsEntry("client_ip", "203.0.113.7");
        Instant occurred = ((LocalDateTime) row.get("occurred_at")).toInstant(ZoneOffset.UTC);
        assertThat(occurred).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(objectMapper.readTree((String) row.get("details")))
                .isEqualTo(objectMapper.readTree("{\"old\":\"A\",\"new\":\"B\"}"));
    }

    @Test
    void recordRollsBackWithCallerAndRequiresATransaction() {
        tx.executeWithoutResult(s -> {
            audit.record("CASE_ASSIGNED", 42L, "agent01", "CASE", "CS-2", null);
            s.setRollbackOnly();
        });
        assertThat(rows()).isEmpty();

        assertThatThrownBy(() -> audit.record("CASE_ASSIGNED", 42L, "agent01", "CASE", "CS-2", null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rows()).isEmpty();
    }

    @Test
    void recordIndependentlySurvivesCallerRollback() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            audit.record("CASE_ASSIGNED", 42L, "agent01", "CASE", "CS-3", null);
            audit.recordIndependently("LOGIN_FAILURE", Outcome.FAILURE, null, null, null, null,
                    Map.of("reason", "bad_password", "username", "age***01"));
            throw new IllegalStateException("caller fails");
        })).isInstanceOf(IllegalStateException.class);

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", "LOGIN_FAILURE")
                .containsEntry("outcome", "FAILURE")
                .containsEntry("correlation_id", correlationId)
                .containsEntry("actor_user_id", null);
    }

    @Test
    void updateAndDeleteOnAuditEventsAreRejected() {
        audit.recordIndependently("LOGOUT", Outcome.SUCCESS, 42L, "agent01", "USER", "42", null);
        Map<String, Object> params = Map.of("cid", correlationId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET action = 'TAMPERED' WHERE correlation_id = :cid", params))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE correlation_id = :cid", params))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(rows()).singleElement().satisfies(r -> assertThat(r).containsEntry("action", "LOGOUT"));
    }

    @Test
    void rejectsActionNamesThatAreNotUpperSnake() {
        assertThatThrownBy(() -> audit.recordIndependently("login failure", Outcome.FAILURE,
                null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows()).isEmpty();
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("""
                SELECT occurred_at, actor_user_id, actor_username, action, entity_type, entity_id, outcome,
                       correlation_id, client_ip, CAST(details AS CHAR) AS details
                FROM audit_events WHERE correlation_id = :cid ORDER BY id
                """, Map.of("cid", correlationId));
    }
}
