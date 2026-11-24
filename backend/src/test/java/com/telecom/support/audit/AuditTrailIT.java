package com.telecom.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.telecom.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditTrailIT extends AbstractWebIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void auditEventsAreAppendOnly() {
        jdbc.update("INSERT INTO audit_events (action, outcome) VALUES ('LOGIN_SUCCESS', 'SUCCESS')");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET outcome = 'FAILURE'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
                .hasMessageContaining("append-only");
    }

    @Test
    void failedLoginIsAuditedWithCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).header("X-Correlation-Id", "audit-login-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"outbound3\",\"password\":\"nope-nope\"}"));

        Integer rows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'LOGIN_FAILURE' AND actor_username = 'outbound3' AND correlation_id = 'audit-login-test-1'
                """, Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
