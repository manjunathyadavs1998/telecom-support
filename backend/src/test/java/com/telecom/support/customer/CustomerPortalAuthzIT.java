package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authorization matrix for every slice 02 customer portal endpoint E1–E17 (design 02-customer backend §4, §9, erratum
 * E2; BR-10, XC-02). Data-driven: anonymous → 401; every staff role (INBOUND_AGENT with and without the approver role,
 * OUTBOUND_AGENT, OUTBOUND_SUPERVISOR, BUSINESS_ANALYST, BUSINESS_AUDITOR) and a CUSTOMER login without a linked
 * customer → 403 with an {@code ACCESS_DENIED} audit row and nothing written; the linked customer passes the check
 * (reads 200, writes reach the business rules: a request built to fail them, so the shared test data is not changed).
 * Mutating requests always carry valid CSRF, an {@code Idempotency-Key} and a valid body, so only authorization decides.
 */
class CustomerPortalAuthzIT extends AbstractMySqlIT {

    private static final String XSRF = "authz-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final Map<String, Cookie> SESSIONS = new ConcurrentHashMap<>();
    private static final String CUSTOMER = "+919900000001";
    private static final List<String> DENIED = List.of(
            "T-IN", "T-IN-REACT", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD", "+919900000004");
    private static final String ZERO_HASH = "0".repeat(64);
    private static final long MISSING = 999_999_999L;

    /** One endpoint: the request every caller sends, and what the linked customer gets with {@code customerBody}. */
    record Endpoint(String id, HttpMethod method, String path, Map<String, Object> body,
                    String customerPath, Map<String, Object> customerBody, int customerStatus) {

        boolean mutating() {
            return method != HttpMethod.GET;
        }
    }

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private List<Endpoint> endpoints;

    @BeforeEach
    void endpoints() {
        long sub = id("SELECT id FROM subscriptions WHERE msisdn = '+919900000001'");
        long invoice = id("SELECT id FROM invoices WHERE invoice_ref = 'INV-T-0003'");
        String payment = jdbc.queryForObject(
                "SELECT reference FROM payments WHERE gateway_session_id = 'test-sess-0003'", Map.of(), String.class);
        String me = "/api/v1/customers/me";

        Map<String, Object> caseBody = new LinkedHashMap<>();
        caseBody.put("categoryCode", "NETWORK");
        caseBody.put("subcategoryCode", "SLOW_DATA");
        caseBody.put("subject", "Authorization probe");
        caseBody.put("description", "Authorization matrix probe request");
        caseBody.put("preferredContact", "SMS");
        Map<String, Object> unknownCategory = new LinkedHashMap<>(caseBody);
        unknownCategory.put("categoryCode", "NO_SUCH_CATEGORY");
        unknownCategory.remove("subcategoryCode");

        Map<String, Object> planChange = Map.of("targetPlanCode", "POST_599", "expectedEffectiveDate", "2030-01-05",
                "expectedCharge", "0.00");
        String verifications = me + "/contact-verifications";
        String planChanges = me + "/subscriptions/" + sub + "/plan-changes";

        endpoints = List.of(
                read("E1", me + "/profile"),
                new Endpoint("E2", HttpMethod.PATCH, me + "/profile", Map.of("aliasName", "Probe", "version", 0),
                        null, Map.of("aliasName", "Probe", "version", 999_999), 409),
                new Endpoint("E3", HttpMethod.POST, verifications,
                        Map.of("channel", "EMAIL", "value", "probe@example.test"),
                        null, Map.of("channel", "SECONDARY_MSISDN", "value", CUSTOMER), 422),
                new Endpoint("E4", HttpMethod.POST, verifications + "/" + MISSING + "/confirm",
                        Map.of("code", "123456"), null, null, 404),
                read("E5", me + "/subscriptions"),
                read("E6", me + "/subscriptions/" + sub + "/plan-options"),
                new Endpoint("E7", HttpMethod.POST, planChanges, planChange,
                        me + "/subscriptions/" + MISSING + "/plan-changes", null, 404),
                read("E8", me + "/promotions"),
                new Endpoint("E9", HttpMethod.POST, me + "/promotions/T_DATA_BOOST/claims",
                        Map.of("subscriptionId", sub, "acceptTerms", true, "termsHash", ZERO_HASH),
                        me + "/promotions/NO_SUCH_PROMO/claims", null, 404),
                read("E10", "/api/v1/case-categories"),
                read("E11", me + "/cases"),
                new Endpoint("E12", HttpMethod.POST, me + "/cases", caseBody, null, unknownCategory, 422),
                read("E13", me + "/invoices"),
                read("E14", me + "/payments"),
                new Endpoint("E15", HttpMethod.POST, me + "/payments",
                        Map.of("invoiceId", invoice, "expectedAmount", "399.00"),
                        null, Map.of("invoiceId", MISSING, "expectedAmount", "399.00"), 404),
                read("E16", me + "/payments/" + payment),
                read("E17", me + "/payments/" + payment + "/receipt"));
    }

    @Test
    void coversEveryCustomerPortalEndpoint() {
        assertThat(endpoints).extracting(Endpoint::id).containsExactly(
                "E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10", "E11", "E12", "E13", "E14", "E15", "E16",
                "E17");
    }

    @Test
    void anonymousIs401() throws Exception {
        for (Endpoint e : endpoints) {
            MvcResult result = mvc.perform(call(e, e.path(), e.body(), null)).andReturn();
            assertProblem(result, 401, "unauthenticated", e.path());
        }
    }

    @Test
    void everyNonCustomerRoleAndAnUnlinkedCustomerIs403AndAudited() throws Exception {
        Map<String, Integer> before = writeCounts();
        List<String> misses = new ArrayList<>();

        for (String username : DENIED) {
            Cookie session = session(username);
            for (Endpoint e : endpoints) {
                MvcResult result = mvc.perform(call(e, e.path(), e.body(), session)).andReturn();
                if (result.getResponse().getStatus() != 403) {
                    misses.add(e.id() + " " + username + " -> " + result.getResponse().getStatus());
                    continue;
                }
                assertProblem(result, 403, "forbidden", e.path());
                // sampled per user + path per minute (01 backend §7), whatever the method (E1/E2, E11/E12 and E14/E15
                // share a path), so a row for this user + path from within the last minute counts
                if (deniedRows(username, e.path()) == 0) {
                    misses.add(e.id() + " " + username + " -> no ACCESS_DENIED row");
                }
            }
        }

        assertThat(misses).isEmpty();
        assertThat(writeCounts()).isEqualTo(before);
    }

    @Test
    void theLinkedCustomerPassesAuthorization() throws Exception {
        Cookie session = session(CUSTOMER);
        for (Endpoint e : endpoints) {
            String path = e.customerPath() != null ? e.customerPath() : e.path();
            Map<String, Object> body = e.customerBody() != null ? e.customerBody() : e.body();
            MvcResult result = mvc.perform(call(e, path, body, session)).andReturn();
            assertThat(result.getResponse().getStatus()).as(e.id() + " " + path).isEqualTo(e.customerStatus());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Endpoint read(String id, String path) {
        return new Endpoint(id, HttpMethod.GET, path, null, null, null, 200);
    }

    private MockHttpServletRequestBuilder call(Endpoint e, String path, Map<String, Object> body, Cookie session)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(e.method(), path);
        List<Cookie> cookies = new ArrayList<>();
        if (session != null) {
            cookies.add(session);
        }
        if (e.mutating()) {
            cookies.add(new Cookie("XSRF-TOKEN", XSRF));
            builder.header("X-XSRF-TOKEN", XSRF)
                    .header("Idempotency-Key", "authz-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.toArray(Cookie[]::new));
        }
        return builder;
    }

    private int deniedRows(String username, String path) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'ACCESS_DENIED' AND actor_username = :username
                  AND occurred_at >= UTC_TIMESTAMP(6) - INTERVAL 90 SECOND
                  AND JSON_UNQUOTE(JSON_EXTRACT(details, '$.path')) = :path
                """, new MapSqlParameterSource().addValue("username", username)
                .addValue("path", path), Integer.class);
    }

    /** Row counts of everything a slice 02 write endpoint can create or change. */
    private Map<String, Integer> writeCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("cases", "payments", "plan_changes", "promotion_claims", "contact_verifications")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Integer.class));
        }
        counts.put("customer_versions", jdbc.queryForObject("SELECT COALESCE(SUM(version), 0) FROM customers",
                Map.of(), Integer.class));
        return counts;
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    /** One session per user for the whole class, each from a fresh client IP (L1 is per IP). */
    private Cookie session(String username) throws Exception {
        Cookie existing = SESSIONS.get(username);
        if (existing != null) {
            return existing;
        }
        int n = IPS.incrementAndGet();
        String ip = "10.61." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with((MockHttpServletRequest r) -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("SESSION")) {
                Cookie session = new Cookie("SESSION", c.getValue());
                SESSIONS.put(username, session);
                return session;
            }
        }
        throw new AssertionError("no session cookie");
    }
}
