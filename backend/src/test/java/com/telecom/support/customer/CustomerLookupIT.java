package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * I1 customer search end to end (design 03-inbound backend R1, §4, §7, §9; INB-01.1, INB-01.4, NFR-PRIV, BR-10). Time
 * moves only through {@link InboundTestClock}. Test data: R__test_users_03_inbound.sql (+919900000006 → CUST-T-0006,
 * account SUSPENDED) and R__test_users_02_customer.sql (+919900000003 is TERMINATED).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class CustomerLookupIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/agent/customer-searches";
    private static final String XSRF = "lookup-xsrf-token";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    LookupRateLimiter limiter;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        limiter.clear();
        jdbc.update("DELETE FROM customer_lookups", Map.of());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM customer_lookups", Map.of());
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", Map.of("t", utc(T0.minus(Duration.ofDays(1)))));
    }

    @Test
    void aHitIsMaskedCarriesALookupTokenAndIsAudited() throws Exception {
        Cookie agent = login("T-IN");

        MvcResult result = search(agent, "+919900000006").andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> matches = json(result);
        assertThat(matches).hasSize(1);
        Map<String, Object> match = matches.get(0);
        assertThat(match).containsOnlyKeys("lookupToken", "customerId", "customerRef", "maskedName", "maskedMsisdn",
                "subscriptionStatus", "customerStatus", "accountStatus", "verifiedUntil");
        assertThat(((Number) match.get("customerId")).longValue()).isEqualTo(customerId("CUST-T-0006"));
        assertThat(match.get("customerRef")).isEqualTo("CUST-T-0006");
        assertThat(match.get("maskedName")).isEqualTo("T*** C*** S***");
        assertThat(match.get("maskedMsisdn")).isEqualTo("+91******0006");
        assertThat(match.get("subscriptionStatus")).isEqualTo("SUSPENDED");
        assertThat(match.get("customerStatus")).isEqualTo("ACTIVE");
        assertThat(match.get("accountStatus")).isEqualTo("SUSPENDED");
        assertThat(match.get("verifiedUntil")).isNull();
        String token = (String) match.get("lookupToken");
        assertThat(token).matches("[A-Za-z0-9_-]{22}");
        String raw = result.getResponse().getContentAsString();
        assertThat(raw).doesNotContain("+919900000006", "Test Customer Suspended", "560001");

        Map<String, Object> lookup = jdbc.queryForMap("SELECT * FROM customer_lookups", Map.of());
        assertThat(lookup.get("token_hash")).isEqualTo(CustomerLookupService.tokenHash(token));
        assertThat(lookup.get("token_hash")).asString().matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(((Number) lookup.get("agent_user_id")).longValue()).isEqualTo(userId("T-IN"));
        assertThat(((Number) lookup.get("customer_id")).longValue()).isEqualTo(customerId("CUST-T-0006"));
        assertThat(instant(lookup.get("expires_at"))).isEqualTo(T0.plus(Duration.ofMinutes(15)));

        Map<String, Object> audit = auditByCorrelation(result);
        assertThat(audit.get("actor_username")).isEqualTo("T-IN");
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(audit.get("entity_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("entity_id")).isEqualTo("CUST-T-0006");
        assertThat(details(audit)).isEqualTo(Map.of("maskedMsisdn", "*********0006", "matched", true));
    }

    @Test
    void aSecondarySubscriptionFindsItsCustomerAndEachHitGetsItsOwnToken() throws Exception {
        Cookie agent = login("T-IN");

        Map<String, Object> first = json(search(agent, "+919900000016").andReturn()).get(0);
        Map<String, Object> second = json(search(agent, "+919900000016").andReturn()).get(0);

        assertThat(first.get("customerRef")).isEqualTo("CUST-T-0006");
        assertThat(first.get("maskedMsisdn")).isEqualTo("+91******0016");
        assertThat(first.get("lookupToken")).isNotEqualTo(second.get("lookupToken"));
        assertThat(count("SELECT COUNT(*) FROM customer_lookups")).isEqualTo(2);
    }

    @Test
    void unknownAndTerminatedNumbersReturnAnEmptyListWithoutAToken() throws Exception {
        Cookie agent = login("T-IN");

        for (String msisdn : List.of("+919812345678", "+919900000003")) {
            MvcResult result = search(agent, msisdn).andExpect(status().isOk()).andReturn();

            assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
            Map<String, Object> audit = auditByCorrelation(result);
            assertThat(audit.get("entity_type")).isNull();
            assertThat(audit.get("entity_id")).isNull();
            assertThat(details(audit)).isEqualTo(Map.of("maskedMsisdn", "*********" + msisdn.substring(9),
                    "matched", false));
        }
        assertThat(count("SELECT COUNT(*) FROM customer_lookups")).isZero();
    }

    @Test
    void verifiedUntilShowsOnlyThisAgentsValidVerifiedCheck() throws Exception {
        long customer = customerId("CUST-T-0006");
        Instant validUntil = T0.plus(Duration.ofMinutes(20));
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', 'VERIFIED', 1, :verifiedAt, :validUntil, :verifiedAt, :verifiedAt)
                """, Map.of("agent", userId("T-IN"), "customer", customer,
                "verifiedAt", utc(T0.minus(Duration.ofMinutes(10))), "validUntil", utc(validUntil)));

        Map<String, Object> mine = json(search(login("T-IN"), "+919900000006").andReturn()).get(0);
        Map<String, Object> other = json(search(login("T-IN-REACT"), "+919900000006").andReturn()).get(0);

        assertThat(Instant.parse((String) mine.get("verifiedUntil"))).isEqualTo(validUntil);
        assertThat(other.get("verifiedUntil")).isNull();

        InboundTestClock.CLOCK.set(validUntil);
        Map<String, Object> expired = json(search(login("T-IN"), "+919900000006").andReturn()).get(0);
        assertThat(expired.get("verifiedUntil")).isNull();
    }

    @Test
    void theThirtyFirstSearchWithinAMinuteIsRateLimitedPerAgent() throws Exception {
        Cookie agent = login("T-IN");
        for (int i = 0; i < 30; i++) {
            InboundTestClock.CLOCK.set(T0.plusSeconds(i));
            search(agent, "+919812345678").andExpect(status().isOk());
        }
        InboundTestClock.CLOCK.set(T0.plusSeconds(40));
        long auditsBefore = lookupAudits();

        MvcResult limited = search(agent, "+919900000006").andReturn();

        assertProblem(limited, 429, "too-many-requests", PATH);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("20");
        assertThat(lookupAudits()).isEqualTo(auditsBefore);
        assertThat(count("SELECT COUNT(*) FROM customer_lookups")).isZero();

        search(login("T-IN-REACT"), "+919900000006").andExpect(status().isOk()); // another agent has its own limit

        InboundTestClock.CLOCK.set(T0.plusSeconds(60).plusMillis(1)); // the first search has left the window
        search(agent, "+919900000006").andExpect(status().isOk());
    }

    @Test
    void anInvalidNumberIsAValidationError() throws Exception {
        Cookie agent = login("T-IN");
        long auditsBefore = lookupAudits();
        for (String bad : List.of("9900000006", "+0919900000006", "+91 99000 00006", "")) {
            assertProblem(search(agent, bad).andReturn(), 400, "validation", PATH);
        }
        assertProblem(mvc.perform(post(PATH).cookie(agent, new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), 400, "validation", PATH);
        assertThat(lookupAudits()).isEqualTo(auditsBefore);
    }

    @Test
    void theFullNumberNeverAppearsInLogsOrAudit(CapturedOutput output) throws Exception {
        Cookie agent = login("T-IN");
        int logStart = output.getAll().length();

        search(agent, "+919900000006").andExpect(status().isOk());
        search(agent, "+919812345678").andExpect(status().isOk());
        search(agent, "12345").andExpect(status().isBadRequest());

        assertThat(output.getAll().substring(logStart)).doesNotContain("+919900000006", "+919812345678", "9900000006");
        assertThat(jdbc.queryForList("SELECT COALESCE(details, '') FROM audit_events WHERE action = 'CUSTOMER_LOOKUP'",
                Map.of(), String.class)).isNotEmpty().noneMatch(d -> d.contains("9900000006") || d.contains("9812345678"));
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        assertProblem(mvc.perform(post(PATH).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"msisdn\":\"+919900000006\"}"))
                .andReturn(), 401, "unauthenticated", PATH);
    }

    @Test
    void customersAndNonInboundStaffAreForbidden() throws Exception {
        long auditsBefore = lookupAudits();
        for (String username : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            assertProblem(search(login(username), "+919900000006").andReturn(), 403, "forbidden", PATH);
        }
        assertThat(lookupAudits()).isEqualTo(auditsBefore);
        assertThat(count("SELECT COUNT(*) FROM customer_lookups")).isZero();
    }

    @Test
    void postWithoutCsrfIsForbidden() throws Exception {
        long auditsBefore = lookupAudits();
        assertProblem(mvc.perform(post(PATH).cookie(login("T-IN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"+919900000006\"}")).andReturn(), 403, "forbidden", PATH);
        assertThat(lookupAudits()).isEqualTo(auditsBefore);
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions search(Cookie session, String msisdn) throws Exception {
        return mvc.perform(post(PATH)
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("msisdn", msisdn))));
    }

    private List<Map<String, Object>> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private Map<String, Object> details(Map<String, Object> audit) throws Exception {
        return objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
    }

    private Map<String, Object> auditByCorrelation(MvcResult result) {
        return jdbc.queryForMap("""
                SELECT actor_username, entity_type, entity_id, outcome, details
                FROM audit_events WHERE action = 'CUSTOMER_LOOKUP' AND correlation_id = :cid
                """, Map.of("cid", result.getResponse().getHeader("X-Correlation-Id")));
    }

    private long lookupAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'CUSTOMER_LOOKUP' AND occurred_at >= :t",
                Map.of("t", utc(T0.minus(Duration.ofDays(1)))), Long.class);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    private long customerId(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                Long.class);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = :u", Map.of("u", username), Long.class);
    }

    private static Instant instant(Object value) {
        LocalDateTime t = value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
        return t.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Logs in like the SPA from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.31." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
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
                return new Cookie("SESSION", c.getValue());
            }
        }
        throw new AssertionError("no session cookie");
    }
}
