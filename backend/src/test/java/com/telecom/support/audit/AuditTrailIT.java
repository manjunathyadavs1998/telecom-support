package com.telecom.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.common.web.CorrelationIdFilter;
import com.telecom.support.identity.Permissions;
import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.PermissionProbeController;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Audit trail (01 backend §4.4, §6, §7, §8, 00-overview §6; BR-03, XC-06). B4: service and append-only trigger cases.
 * B9: login, logout and ACCESS_DENIED rows over HTTP, including the per-minute sampling of denials. Time moves only
 * through the {@link MutableClock}; nothing sleeps. Every test uses its own client IPs and (user, path) pairs, because
 * the limiter and the sampling map keep state for the whole context.
 */
@Import(PermissionProbeController.class)
class AuditTrailIT extends AbstractMySqlIT {

    private static final MutableClock CLOCK = new MutableClock(Instant.now());
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PRE_LOGIN_TOKEN = "audit-xsrf-token";

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    AuditService audit;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper objectMapper;

    private String correlationId;

    static Clock fixedClock() {
        return CLOCK;
    }

    @BeforeEach
    void bindRequest() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
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

    @Test
    void loginAndLogoutWriteRowsWithCorrelationIdAndClientIp() throws Exception {
        String ip = nextIp();
        MvcResult login = login(ip, "T-IN", TEST_PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie session = cookie(login, "SESSION");
        Cookie xsrf = cookie(login, "XSRF-TOKEN");
        long userId = userId("T-IN");

        assertThat(rows(login, "LOGIN_SUCCESS")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "SUCCESS").containsEntry("actor_user_id", userId)
                .containsEntry("actor_username", "T-IN").containsEntry("entity_type", "USER")
                .containsEntry("entity_id", String.valueOf(userId)).containsEntry("client_ip", ip));

        MvcResult logout = mvc.perform(from(ip, post(LOGOUT)).cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(rows(logout, "LOGOUT")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("outcome", "SUCCESS").containsEntry("actor_user_id", userId)
                .containsEntry("entity_type", "USER").containsEntry("client_ip", ip));
    }

    @Test
    void loginFailureRowHasReasonAndMaskedUsername() throws Exception {
        String ip = nextIp();
        MvcResult failed = login(ip, "T-OUT-SUP", "Wrong-password-1").andExpect(status().isUnauthorized()).andReturn();

        assertThat(rows(failed, "LOGIN_FAILURE")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("outcome", "FAILURE").containsEntry("actor_user_id", null)
                    .containsEntry("actor_username", null).containsEntry("client_ip", ip);
            assertThat(details(row)).isEqualTo(Map.of("reason", "bad_password", "username", "T-O****UP"));
        });
    }

    @Test
    void preAuthorizeDenialWritesOneSampledRowPerUserAndPathPerMinute() throws Exception {
        String ip = nextIp();
        Cookie session = cookie(login(ip, "T-BUS", TEST_PASSWORD).andExpect(status().isOk()).andReturn(), "SESSION");
        String path = PermissionProbeController.PATH + "/" + Permissions.CASE_READ;
        Map<String, Object> expectedDetails = Map.of("method", "GET", "path", path, "reason", "permission",
                "requiredAuthority", Permissions.CASE_READ);
        long userId = userId("T-BUS");

        MvcResult first = mvc.perform(from(ip, get(path)).cookie(session)).andExpect(status().isForbidden()).andReturn();
        assertDeniedRow(first, userId, "T-BUS", ip, expectedDetails);

        MvcResult second = mvc.perform(from(ip, get(path)).cookie(session)).andExpect(status().isForbidden()).andReturn();
        assertThat(rows(second, DeniedAccessRecorder.ACTION)).isEmpty();

        CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(1)));
        MvcResult third = mvc.perform(from(ip, get(path)).cookie(session)).andExpect(status().isForbidden()).andReturn();
        assertDeniedRow(third, userId, "T-BUS", ip, expectedDetails);
    }

    @Test
    void urlLevelCsrfDenialWritesOneSampledRowPerUserAndPathPerMinute() throws Exception {
        String ip = nextIp();
        Cookie session = cookie(login(ip, "T-OUT", TEST_PASSWORD).andExpect(status().isOk()).andReturn(), "SESSION");
        Map<String, Object> expectedDetails = Map.of("method", "POST", "path", LOGOUT, "reason", "csrf");
        long userId = userId("T-OUT");

        MvcResult first = mvc.perform(from(ip, post(LOGOUT)).cookie(session)).andExpect(status().isForbidden())
                .andReturn();
        assertDeniedRow(first, userId, "T-OUT", ip, expectedDetails);

        MvcResult second = mvc.perform(from(ip, post(LOGOUT)).cookie(session)).andExpect(status().isForbidden())
                .andReturn();
        assertThat(rows(second, DeniedAccessRecorder.ACTION)).isEmpty();

        CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(1)));
        MvcResult third = mvc.perform(from(ip, post(LOGOUT)).cookie(session)).andExpect(status().isForbidden())
                .andReturn();
        assertDeniedRow(third, userId, "T-OUT", ip, expectedDetails);
    }

    @Test
    void anonymousDenialsAreSampledPerClientIp() throws Exception {
        String ip = nextIp();
        String otherIp = nextIp();
        Map<String, Object> expectedDetails = Map.of("method", "POST", "path", LOGOUT, "reason", "csrf");

        MvcResult first = mvc.perform(from(ip, post(LOGOUT))).andExpect(status().isForbidden()).andReturn();
        assertDeniedRow(first, null, null, ip, expectedDetails);
        MvcResult again = mvc.perform(from(ip, post(LOGOUT))).andExpect(status().isForbidden()).andReturn();
        assertThat(rows(again, DeniedAccessRecorder.ACTION)).isEmpty();
        MvcResult other = mvc.perform(from(otherIp, post(LOGOUT))).andExpect(status().isForbidden()).andReturn();
        assertDeniedRow(other, null, null, otherIp, expectedDetails);
    }

    @Test
    void samplingMapIsBoundedAndOverflowNeverDropsARow() {
        DeniedAccessRecorder recorder = new DeniedAccessRecorder(audit, CLOCK, 3);
        for (int i = 0; i < 7; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bounded/" + i);
            request.setRemoteAddr("203.0.113.7");
            recorder.record(request, new AccessDeniedException("probe"));
            assertThat(recorder.trackedKeys()).isLessThanOrEqualTo(3);
        }

        assertThat(rows()).hasSize(7).allSatisfy(row -> assertThat(row)
                .containsEntry("action", DeniedAccessRecorder.ACTION).containsEntry("outcome", "DENIED"));
    }

    private void assertDeniedRow(MvcResult result, Long userId, String username, String ip,
                                 Map<String, Object> expectedDetails) {
        assertThat(rows(result, DeniedAccessRecorder.ACTION)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("outcome", "DENIED").containsEntry("actor_user_id", userId)
                    .containsEntry("actor_username", username).containsEntry("entity_type", null)
                    .containsEntry("client_ip", ip);
            assertThat(details(row)).isEqualTo(expectedDetails);
        });
    }

    /** Rows of {@code action} written under the response's correlation id. */
    private List<Map<String, Object>> rows(MvcResult result, String action) {
        String cid = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(cid).isNotBlank();
        return jdbc.queryForList("""
                SELECT actor_user_id, actor_username, entity_type, entity_id, outcome, client_ip,
                       CAST(details AS CHAR) AS details
                FROM audit_events WHERE correlation_id = :cid AND action = :action ORDER BY id
                """, Map.of("cid", cid, "action", action));
    }

    private Map<String, Object> details(Map<String, Object> row) throws Exception {
        return objectMapper.readValue((String) row.get("details"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = :u", Map.of("u", username), Long.class);
    }

    /** What the SPA sends: the bootstrap XSRF-TOKEN cookie echoed in the header (not spring-security-test's csrf()). */
    private org.springframework.test.web.servlet.ResultActions login(String ip, String username, String password)
            throws Exception {
        return mvc.perform(from(ip, post(LOGIN))
                .cookie(new Cookie("XSRF-TOKEN", PRE_LOGIN_TOKEN))
                .header("X-XSRF-TOKEN", PRE_LOGIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private static MockHttpServletRequestBuilder from(String ip, MockHttpServletRequestBuilder builder) {
        return builder.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    /** The last cookie of that name on the response (the one a browser keeps). */
    private static Cookie cookie(MvcResult result, String name) {
        Cookie found = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals(name)) {
                found = new Cookie(name, c.getValue());
            }
        }
        assertThat(found).as("cookie " + name).isNotNull();
        return found;
    }

    private static String nextIp() {
        int n = IPS.incrementAndGet();
        return "10.10." + (n / 250) + "." + (n % 250 + 1);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("""
                SELECT occurred_at, actor_user_id, actor_username, action, entity_type, entity_id, outcome,
                       correlation_id, client_ip, CAST(details AS CHAR) AS details
                FROM audit_events WHERE correlation_id = :cid ORDER BY id
                """, Map.of("cid", correlationId));
    }
}
