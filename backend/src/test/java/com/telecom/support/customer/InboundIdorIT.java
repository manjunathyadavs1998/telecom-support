package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cross-customer access (design 03-inbound backend R2, R5, R9, R12, §4, §9; NFR-INTEG, BR-10). The approver agent is
 * verified for customer A ({@code CUST-T-0001}) only; customer B is {@code CUST-T-0006}. B's case reference (I10), B's
 * subscription (I12), B's identity check id (I3) and A's lookup token on B's id (I2) → 404 under A; every ✔V route on
 * B's id → 403 {@code verification-required}; nothing is created or changed. Shares the inbound context.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class InboundIdorIT extends AbstractMySqlIT {

    private static final String XSRF = "inbound-idor-xsrf";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final String AGENT = "T-IN-REACT";

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private long a;
    private long b;
    private Cookie agent;

    @BeforeEach
    void setUp() throws Exception {
        InboundTestClock.CLOCK.set(T0);
        cleanTables();
        a = id("SELECT id FROM customers WHERE customer_ref = 'CUST-T-0001'");
        b = id("SELECT id FROM customers WHERE customer_ref = 'CUST-T-0006'");
        verify(a);
        agent = login(AGENT);
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
        InboundTestClock.CLOCK.set(T0);
    }

    private void cleanTables() {
        Map<String, Object> from = Map.of("t", utc(T0.minus(Duration.ofDays(200))));
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM customer_lookups WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM idempotency_keys WHERE operation IN ('CASE_CREATE_ONBEHALF', 'REACTIVATION_CREATE')",
                Map.of());
    }

    @Test
    void anotherCustomersCaseReferenceIsNotFoundUnderA() throws Exception {
        String bCase = jdbc.queryForObject("""
                SELECT c.reference FROM cases c JOIN accounts a ON a.id = c.account_id
                WHERE a.account_ref = 'ACC-T-0006' ORDER BY c.id LIMIT 1
                """, Map.of(), String.class);
        String path = base(a) + "/cases/" + bCase;
        assertProblem(mvc.perform(get(path).cookie(agent)).andReturn(), 404, "not-found", path);
        // B's case does not show up in A's list either
        MvcResult list = mvc.perform(get(base(a) + "/cases").cookie(agent)).andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(bCase);
    }

    @Test
    void anotherCustomersSubscriptionIsNotFoundForCreateOnBehalf() throws Exception {
        long bSub = id("""
                SELECT s.id FROM subscriptions s JOIN accounts a ON a.id = s.account_id
                WHERE a.account_ref = 'ACC-T-0006' AND s.is_primary
                """);
        long cases = count("cases");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCode", "NETWORK");
        body.put("subcategoryCode", "SLOW_DATA");
        body.put("subscriptionId", bSub);
        body.put("subject", "IDOR probe");
        body.put("customerStatement", "IDOR probe with another customer's subscription");
        body.put("preferredContact", "SMS");

        String path = base(a) + "/cases";
        assertProblem(postJson(path, body, true).andReturn(), 404, "not-found", path);
        assertThat(count("cases")).isEqualTo(cases);
    }

    @Test
    void anotherCustomersIdentityCheckCannotBeConfirmedUnderA() throws Exception {
        long bCheck = pendingOtp(b);
        String path = base(a) + "/identity-checks/" + bCheck + "/confirm";

        assertProblem(postJson(path, Map.of("code", "123456"), false).andReturn(), 404, "not-found", path);

        assertThat(jdbc.queryForMap("SELECT status, attempts FROM identity_checks WHERE id = :id",
                Map.of("id", bCheck))).containsEntry("status", "PENDING").containsEntry("attempts", 0);
    }

    @Test
    void aLookupTokenForAIsNotFoundOnBsId() throws Exception {
        String tokenA = lookupToken("+919900000001");
        long checks = count("identity_checks");
        String path = base(b) + "/identity-checks";

        assertProblem(postJson(path, Map.of("lookupToken", tokenA, "method", "OTP"), false).andReturn(), 404,
                "not-found", path);

        assertThat(count("identity_checks")).isEqualTo(checks);
        assertProblem(mvc.perform(get(path + "/current").cookie(agent)).andReturn(), 404, "not-found",
                path + "/current");
    }

    @Test
    void aCheckForADoesNotOpenAnyVerifiedRouteOfB() throws Exception {
        long requests = count("reactivation_requests");
        long cases = count("cases");
        for (String suffix : List.of("/profile", "/subscriptions", "/invoices", "/payments", "/cases",
                "/cases/SR-ANY", "/reactivation-eligibility", "/reactivations")) {
            String path = base(b) + suffix;
            assertProblem(mvc.perform(get(path).cookie(agent)).andReturn(), 403, "verification-required", path);
        }
        String cases12 = base(b) + "/cases";
        assertProblem(postJson(cases12, Map.of("categoryCode", "NETWORK", "subcategoryCode", "SLOW_DATA",
                "subject", "IDOR probe", "customerStatement", "IDOR probe on another customer",
                "preferredContact", "SMS"), true).andReturn(), 403, "verification-required", cases12);
        String reactivations = base(b) + "/reactivations";
        assertProblem(postJson(reactivations, Map.of("hours", 2, "reason", "IDOR probe on another customer"), true)
                .andReturn(), 403, "verification-required", reactivations);

        assertThat(count("reactivation_requests")).isEqualTo(requests);
        assertThat(count("cases")).isEqualTo(cases);
    }

    // ------------------------------------------------------------------ helpers

    private static String base(long customerId) {
        return "/api/v1/agent/customers/" + customerId;
    }

    private ResultActions postJson(String path, Object body, boolean idempotent) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .cookie(agent, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (idempotent) {
            request.header("Idempotency-Key", "inbound-idor-" + UUID.randomUUID());
        }
        return mvc.perform(request);
    }

    private String lookupToken(String msisdn) throws Exception {
        MvcResult result = postJson("/api/v1/agent/customer-searches", Map.of("msisdn", msisdn), false)
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> matches = objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<>() {});
        return (String) matches.get(0).get("lookupToken");
    }

    /** The agent's VERIFIED KBA check for the customer, valid for 30 minutes from now. */
    private void verify(long customerId) {
        Instant now = InboundTestClock.CLOCK.instant();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                SELECT u.id, :customer, 'KBA', 'VERIFIED', 1, :t, :validUntil, :t, :t FROM users u
                WHERE u.username = :username
                """, new MapSqlParameterSource()
                .addValue("username", AGENT)
                .addValue("customer", customerId)
                .addValue("t", utc(now))
                .addValue("validUntil", utc(now.plus(Duration.ofMinutes(30)))));
    }

    /** A PENDING OTP check of the agent for the customer (code unknown to the test). */
    private long pendingOtp(long customerId) {
        Instant now = InboundTestClock.CLOCK.instant();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, code_hmac, attempts,
                                             expires_at, created_at, updated_at)
                SELECT u.id, :customer, 'OTP', 'PENDING', :hmac, 0, :expires, :t, :t FROM users u
                WHERE u.username = :username
                """, new MapSqlParameterSource()
                .addValue("username", AGENT)
                .addValue("customer", customerId)
                .addValue("hmac", "f".repeat(64))
                .addValue("expires", utc(now.plus(Duration.ofMinutes(10))))
                .addValue("t", utc(now)), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Long.class);
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Logs in like the SPA from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.74." + (n / 250) + "." + (n % 250 + 1);
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
