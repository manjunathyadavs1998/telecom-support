package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authorization matrix for every slice 03 inbound endpoint I1–I15 (design 03-inbound backend §4, §5, §9; BR-10, XC-02).
 * Data-driven: anonymous → 401; CUSTOMER, OUTBOUND_AGENT, OUTBOUND_SUPERVISOR, BUSINESS_ANALYST, BUSINESS_AUDITOR → 403
 * {@code forbidden} (never {@code verification-required}) and nothing written; an inbound agent without a check →
 * 403 {@code verification-required} on every ✔V route; INBOUND_AGENT without REACTIVATION_APPROVER (verified) → 403
 * {@code forbidden} on I13/I14; a POST without CSRF → 403; {@code ACCESS_DENIED} sampled per user + path per minute.
 * Mutating requests carry valid CSRF, an {@code Idempotency-Key} and a valid body, so only authorization decides.
 * Shares the inbound context (clock, logging property, import).
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class InboundAuthzIT extends AbstractMySqlIT {

    private static final String XSRF = "inbound-authz-xsrf";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final List<String> FORBIDDEN = List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD");
    private static final long MISSING = 999_999_999L;
    /** I5–I10, I12–I15: behind the central verified scope (R5). */
    private static final Set<String> VERIFIED_ROUTES = Set.of("I5", "I6", "I7", "I8", "I9", "I10", "I12", "I13", "I14",
            "I15");

    record Endpoint(String id, HttpMethod method, String path, Map<String, Object> body) {

        boolean mutating() {
            return method != HttpMethod.GET;
        }
    }

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private long c6;
    private List<Endpoint> endpoints;

    @BeforeEach
    void endpoints() {
        InboundTestClock.CLOCK.set(T0);
        cleanTables();
        c6 = jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = 'CUST-T-0006'", Map.of(), Long.class);
        String customer = "/api/v1/agent/customers/" + c6;
        Map<String, Object> caseBody = new LinkedHashMap<>();
        caseBody.put("categoryCode", "NETWORK");
        caseBody.put("subcategoryCode", "SLOW_DATA");
        caseBody.put("subject", "Authorization probe");
        caseBody.put("customerStatement", "Authorization matrix probe request");
        caseBody.put("preferredContact", "SMS");
        endpoints = List.of(
                new Endpoint("I1", HttpMethod.POST, "/api/v1/agent/customer-searches",
                        Map.of("msisdn", "+919900000006")),
                new Endpoint("I2", HttpMethod.POST, customer + "/identity-checks",
                        Map.of("lookupToken", "a".repeat(22), "method", "OTP")),
                new Endpoint("I3", HttpMethod.POST, customer + "/identity-checks/" + MISSING + "/confirm",
                        Map.of("code", "123456")),
                get("I4", customer + "/identity-checks/current"),
                get("I5", customer + "/profile"),
                get("I6", customer + "/subscriptions"),
                get("I7", customer + "/invoices"),
                get("I8", customer + "/payments"),
                get("I9", customer + "/cases"),
                get("I10", customer + "/cases/SR-NO-SUCH-CASE"),
                get("I11", "/api/v1/agent/case-categories"),
                new Endpoint("I12", HttpMethod.POST, customer + "/cases", caseBody),
                get("I13", customer + "/reactivation-eligibility"),
                new Endpoint("I14", HttpMethod.POST, customer + "/reactivations",
                        Map.of("hours", 2, "reason", "Authorization matrix probe reason")),
                get("I15", customer + "/reactivations"));
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
        InboundTestClock.CLOCK.set(T0);
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t",
                Map.of("t", utc(T0.minus(Duration.ofDays(200)))));
        jdbc.update("DELETE FROM idempotency_keys WHERE operation IN ('CASE_CREATE_ONBEHALF', 'REACTIVATION_CREATE')",
                Map.of());
    }

    @Test
    void coversEveryInboundEndpoint() {
        assertThat(endpoints).extracting(Endpoint::id).containsExactly("I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8",
                "I9", "I10", "I11", "I12", "I13", "I14", "I15");
    }

    @Test
    void anonymousIs401() throws Exception {
        for (Endpoint e : endpoints) {
            assertProblem(mvc.perform(call(e, null, true)).andReturn(), 401, "unauthenticated", e.path());
        }
    }

    @Test
    void customersOutboundAndBusinessRolesAreForbiddenNotVerificationRequired() throws Exception {
        Map<String, Long> before = writeCounts();
        List<String> misses = new ArrayList<>();
        for (String username : FORBIDDEN) {
            Cookie session = login(username);
            for (Endpoint e : endpoints) {
                MvcResult result = mvc.perform(call(e, session, true)).andReturn();
                if (result.getResponse().getStatus() != 403) {
                    misses.add(e.id() + " " + username + " -> " + result.getResponse().getStatus());
                    continue;
                }
                assertProblem(result, 403, "forbidden", e.path());
            }
        }
        assertThat(misses).isEmpty();
        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void anInboundAgentWithoutACheckIsVerificationRequiredOnEveryVerifiedRoute() throws Exception {
        Map<String, Long> before = writeCounts();
        Cookie agent = login("T-IN-REACT");
        for (Endpoint e : endpoints) {
            if (VERIFIED_ROUTES.contains(e.id())) {
                assertProblem(mvc.perform(call(e, agent, true)).andReturn(), 403, "verification-required",
                        e.path());
            }
        }
        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void aVerifiedInboundAgentWithoutTheApproverRoleIsForbiddenOnEligibilityAndCreate() throws Exception {
        verify("T-IN", c6);
        Map<String, Long> before = writeCounts();
        Cookie agent = login("T-IN");
        for (Endpoint e : endpoints) {
            if (e.id().equals("I13") || e.id().equals("I14")) {
                assertProblem(mvc.perform(call(e, agent, true)).andReturn(), 403, "forbidden", e.path());
            }
        }
        // the check itself is valid: a verified read passes
        Endpoint profile = endpoints.stream().filter(e -> e.id().equals("I5")).findFirst().orElseThrow();
        mvc.perform(call(profile, agent, true)).andExpect(status().isOk());
        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void aDeniedReadDoesNotUseUpTheOncePerCheckAccessRow() throws Exception {
        verify("T-IN", c6);
        long check = jdbc.queryForObject("""
                SELECT MAX(ic.id) FROM identity_checks ic JOIN users u ON u.id = ic.agent_user_id
                WHERE u.username = 'T-IN' AND ic.customer_id = :customer
                """, Map.of("customer", c6), Long.class);
        Cookie agent = login("T-IN");
        Endpoint eligibility = endpoints.stream().filter(e -> e.id().equals("I13")).findFirst().orElseThrow();
        Endpoint profile = endpoints.stream().filter(e -> e.id().equals("I5")).findFirst().orElseThrow();

        assertProblem(mvc.perform(call(eligibility, agent, true)).andReturn(), 403, "forbidden", eligibility.path());
        assertThat(accessedPaths(check)).as("denied I13 writes no access row (R6, review C3)").isEmpty();
        mvc.perform(call(profile, agent, true)).andExpect(status().isOk());
        mvc.perform(call(profile, agent, true)).andExpect(status().isOk());
        assertThat(accessedPaths(check)).containsExactly(profile.path());
    }

    private List<String> accessedPaths(long check) {
        return jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(details, '$.path')) FROM audit_events
                WHERE action = 'CUSTOMER_RECORD_ACCESSED' AND JSON_EXTRACT(details, '$.checkId') = :check
                """, Map.of("check", check), String.class);
    }

    @Test
    void aPostWithoutCsrfIsForbidden() throws Exception {
        verify("T-IN-REACT", c6);
        Map<String, Long> before = writeCounts();
        Cookie agent = login("T-IN-REACT");
        for (Endpoint e : endpoints) {
            if (e.mutating()) {
                assertProblem(mvc.perform(call(e, agent, false)).andReturn(), 403, "forbidden", e.path());
            }
        }
        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void accessDeniedIsRecordedOncePerUserAndPathPerMinute() throws Exception {
        Cookie outbound = login("T-OUT");
        String path = "/api/v1/agent/customers/" + (700_000_000L + IPS.incrementAndGet() * 1_000L
                + System.nanoTime() % 1_000) + "/profile";
        Endpoint probe = get("probe", path);

        mvc.perform(call(probe, outbound, true)).andExpect(status().isForbidden());
        assertThat(deniedRows("T-OUT", path)).isEqualTo(1);
        InboundTestClock.CLOCK.set(T0.plusSeconds(59));
        mvc.perform(call(probe, outbound, true)).andExpect(status().isForbidden());
        assertThat(deniedRows("T-OUT", path)).as("repeat within 1 min").isEqualTo(1);
        InboundTestClock.CLOCK.set(T0.plusSeconds(61));
        mvc.perform(call(probe, outbound, true)).andExpect(status().isForbidden());
        assertThat(deniedRows("T-OUT", path)).as("after 61 s").isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private static Endpoint get(String id, String path) {
        return new Endpoint(id, HttpMethod.GET, path, null);
    }

    private MockHttpServletRequestBuilder call(Endpoint e, Cookie session, boolean csrf) throws Exception {
        MockHttpServletRequestBuilder builder = request(e.method(), e.path());
        List<Cookie> cookies = new ArrayList<>();
        if (session != null) {
            cookies.add(session);
        }
        if (e.mutating()) {
            if (csrf) {
                cookies.add(new Cookie("XSRF-TOKEN", XSRF));
                builder.header("X-XSRF-TOKEN", XSRF);
            }
            builder.header("Idempotency-Key", "inbound-authz-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(e.body()));
        }
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.toArray(Cookie[]::new));
        }
        return builder;
    }

    /** Row counts of everything an inbound endpoint can create. */
    private Map<String, Long> writeCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("customer_lookups", "identity_checks", "cases", "reactivation_requests",
                "notifications")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Long.class));
        }
        return counts;
    }

    private long deniedRows(String username, String path) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'ACCESS_DENIED' AND actor_username = :username
                  AND JSON_UNQUOTE(JSON_EXTRACT(details, '$.path')) = :path
                """, new MapSqlParameterSource().addValue("username", username).addValue("path", path), Long.class);
    }

    /** A KBA check of {@code username} for the customer, VERIFIED now for 30 minutes. */
    private void verify(String username, long customerId) {
        Instant now = InboundTestClock.CLOCK.instant();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                SELECT u.id, :customer, 'KBA', 'VERIFIED', 1, :t, :validUntil, :t, :t FROM users u
                WHERE u.username = :username
                """, new MapSqlParameterSource()
                .addValue("username", username)
                .addValue("customer", customerId)
                .addValue("t", utc(now))
                .addValue("validUntil", utc(now.plus(Duration.ofMinutes(30)))));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Logs in like the SPA from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.73." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with((MockHttpServletRequest r) -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("SESSION")) {
                return new Cookie("SESSION", c.getValue());
            }
        }
        throw new AssertionError("no SESSION cookie");
    }
}
