package com.telecom.support.reactivation;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.InboundTestClock;
import com.telecom.support.customer.VerifiedProbeController;
import com.telecom.support.reactivation.ReactivationEligibilityService.EligibilityReason;
import com.telecom.support.reactivation.ReactivationEligibilityService.ReactivationEligibility;
import com.telecom.support.reactivation.ReactivationPolicyRepository.Policy;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Reactivation eligibility I13 (design 03-inbound backend R13, §4, §9; INB-04.1/04.2/04.6). Runs at the shared inbound
 * clock's T0 (2030-01-15 11:30 Asia/Kolkata); VERIFIED checks and the requests that drive the limits are inserted by
 * the test and removed afterwards (every test row is created at or after T0 − 60 days, every seeded row long before).
 * The hard-coded FRAUD/LEGAL/REGULATORY rule is proven with a policy stub that the DB CHECK would refuse.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class ReactivationEligibilityIT extends AbstractMySqlIT {

    private static final String XSRF = "reactivation-eligibility-xsrf";
    private static final Instant T0 = InboundTestClock.T0;
    private static final Instant TEST_ROWS_FROM = T0.minus(Duration.ofDays(60));
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final AtomicInteger REFS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AccountLookup accountLookup;

    @Autowired
    ReactivationRepository requests;

    @Autowired
    ReactivationProperties properties;

    private long c1;
    private long c6;
    private long c7;
    private long c8;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        cleanTables();
        c1 = customerId("CUST-T-0001");
        c6 = customerId("CUST-T-0006");
        c7 = customerId("CUST-T-0007");
        c8 = customerId("CUST-T-0008");
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
        reinstateAccount("ACC-T-0001");
        suspendAccount("ACC-T-0006", "NON_PAYMENT");
        jdbc.update("UPDATE reactivation_policies SET max_hours = 24 WHERE suspension_reason = 'NON_PAYMENT'",
                Map.of());
        InboundTestClock.CLOCK.set(T0);
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM reactivation_requests WHERE created_at >= :t", Map.of("t", utc(TEST_ROWS_FROM)));
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", Map.of("t", utc(TEST_ROWS_FROM)));
    }

    @Test
    void aNonPaymentSuspensionWithSuspendedServicesIsEligibleForThePolicyHours() throws Exception {
        verify("T-IN-REACT", c6);

        Map<String, Object> body = eligibility(login("T-IN-REACT"), c6);

        assertThat(body).containsOnlyKeys("eligible", "reasons", "suspensionReason", "maxHours", "suspendedMsisdns",
                "openRequestReference");
        assertThat(body).containsEntry("eligible", true).containsEntry("reasons", List.of())
                .containsEntry("suspensionReason", "NON_PAYMENT").containsEntry("maxHours", 24)
                .containsEntry("suspendedMsisdns", List.of("+919900000006", "+919900000016"))
                .containsEntry("openRequestReference", null);

        jdbc.update("UPDATE reactivation_policies SET max_hours = 6 WHERE suspension_reason = 'NON_PAYMENT'", Map.of());
        assertThat(eligibility(login("T-IN-REACT"), c6)).containsEntry("eligible", true).containsEntry("maxHours", 6);
    }

    @Test
    void fraudIsProhibitedAndNotEligibleByPolicy() throws Exception {
        verify("T-IN-REACT", c7);

        Map<String, Object> body = eligibility(login("T-IN-REACT"), c7);

        assertThat(body).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("PROHIBITED_REASON", "REASON_NOT_ELIGIBLE"))
                .containsEntry("suspensionReason", "FRAUD").containsEntry("maxHours", 24)
                .containsEntry("suspendedMsisdns", List.of("+919900000007"));
    }

    @Test
    void fraudLegalAndRegulatoryStayProhibitedEvenWithAnEligiblePolicy() {
        ReactivationPolicyRepository everythingEligible = new ReactivationPolicyRepository(jdbc) {
            @Override
            Optional<Policy> find(String suspensionReason) {
                return Optional.of(new Policy(suspensionReason, true, 12));
            }
        };
        ReactivationEligibilityService service = new ReactivationEligibilityService(accountLookup,
                everythingEligible, requests, properties, clock, ZoneId.of("Asia/Kolkata"));
        long agent = userId("T-IN-REACT");

        for (String reason : List.of("FRAUD", "LEGAL", "REGULATORY")) {
            suspendAccount("ACC-T-0006", reason);
            ReactivationEligibility result = service.evaluate(accountLookup.accountFor(c6).orElseThrow(), agent);
            assertThat(result.eligible()).as(reason).isFalse();
            assertThat(result.reasons()).as(reason).containsExactly(EligibilityReason.PROHIBITED_REASON);
            assertThat(result.maxHours()).as(reason).isEqualTo(12);
        }
        suspendAccount("ACC-T-0006", "NON_PAYMENT");
        assertThat(service.evaluate(accountLookup.accountFor(c6).orElseThrow(), agent).eligible()).isTrue();
    }

    @Test
    void aReasonWhosePolicyIsNotEligibleIsReported() throws Exception {
        suspendAccount("ACC-T-0006", "CUSTOMER_REQUEST");
        verify("T-IN-REACT", c6);

        assertThat(eligibility(login("T-IN-REACT"), c6)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("REASON_NOT_ELIGIBLE"))
                .containsEntry("suspensionReason", "CUSTOMER_REQUEST");
    }

    @Test
    void activeOrInactiveAccountsAndSuspensionsWithoutSuspendedServiceAreReported() throws Exception {
        Cookie agent = login("T-IN-REACT");
        verify("T-IN-REACT", c1);
        verify("T-IN-REACT", c8);

        assertThat(eligibility(agent, c1)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("NOT_SUSPENDED", "NO_SUSPENDED_SERVICE"))
                .containsEntry("suspensionReason", null).containsEntry("maxHours", null)
                .containsEntry("suspendedMsisdns", List.of());
        assertThat(eligibility(agent, c8)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("CUSTOMER_INACTIVE", "NOT_SUSPENDED", "NO_SUSPENDED_SERVICE"));

        suspendAccount("ACC-T-0001", "NON_PAYMENT"); // its subscriptions stay ACTIVE
        assertThat(eligibility(agent, c1)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("NO_SUSPENDED_SERVICE"))
                .containsEntry("suspensionReason", "NON_PAYMENT").containsEntry("maxHours", 24);
    }

    @Test
    void anOpenRequestBlocksAndIsNamed() throws Exception {
        long check = verify("T-IN-REACT", c6);
        String reference = insertRequest("ACC-T-0006", "T-IN", check, "PENDING", T0.minus(Duration.ofMinutes(5)));

        Map<String, Object> body = eligibility(login("T-IN-REACT"), c6);

        assertThat(body).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("OPEN_REQUEST", "ACCOUNT_LIMIT_REACHED"))
                .containsEntry("openRequestReference", reference);
    }

    @Test
    void theAccountLimitCountsNonFailedRequestsOfTheLast30Days() throws Exception {
        Cookie agent = login("T-IN-REACT");
        long check = verify("T-IN-REACT", c6);
        insertRequest("ACC-T-0006", "T-IN", check, "FAILED", T0.minus(Duration.ofDays(2)));
        insertRequest("ACC-T-0006", "T-IN", check, "ENDED", T0.minus(Duration.ofDays(31)));
        assertThat(eligibility(agent, c6)).containsEntry("eligible", true);

        insertRequest("ACC-T-0006", "T-IN", check, "ENDED", T0.minus(Duration.ofDays(10)));
        assertThat(eligibility(agent, c6)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("ACCOUNT_LIMIT_REACHED"))
                .containsEntry("openRequestReference", null);

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofDays(20)).plus(Duration.ofMinutes(1)));
        verify("T-IN-REACT", c6);
        assertThat(eligibility(login("T-IN-REACT"), c6)).containsEntry("eligible", true);
    }

    @Test
    void theAgentLimitCountsTheAgentsNonFailedRequestsOfTodayInKolkata() throws Exception {
        Cookie agent = login("T-IN-REACT");
        long c9Check = verify("T-IN-REACT", customerId("CUST-T-0009"));
        verify("T-IN-REACT", c6);
        Instant startOfToday = Instant.parse("2030-01-14T18:30:00Z"); // 2030-01-15 00:00 Asia/Kolkata
        insertRequest("ACC-T-0009", "T-IN-REACT", c9Check, "ENDED", startOfToday.minusSeconds(1)); // yesterday
        insertRequest("ACC-T-0009", "T-IN-REACT", c9Check, "FAILED", startOfToday.plusSeconds(1));
        for (int i = 0; i < 9; i++) {
            insertRequest("ACC-T-0009", "T-IN-REACT", c9Check, "ENDED", startOfToday.plus(Duration.ofMinutes(i)));
        }
        assertThat(eligibility(agent, c6)).containsEntry("eligible", true);

        insertRequest("ACC-T-0009", "T-IN-REACT", c9Check, "EXPIRED", startOfToday);
        assertThat(eligibility(agent, c6)).containsEntry("eligible", false)
                .containsEntry("reasons", List.of("AGENT_LIMIT_REACHED"));

        // another agent is not limited by T-IN-REACT's requests
        ReactivationEligibilityService service = new ReactivationEligibilityService(accountLookup,
                new ReactivationPolicyRepository(jdbc), requests, properties, clock, ZoneId.of("Asia/Kolkata"));
        assertThat(service.evaluate(accountLookup.accountFor(c6).orElseThrow(), userId("T-IN")).eligible()).isTrue();
    }

    @Test
    void anonymousIs401OtherRolesAre403AndAVerifiedCheckIsRequired() throws Exception {
        verify("T-IN", c6);
        String path = path(c6);
        assertProblem(mvc.perform(get(path)).andReturn(), 401, "unauthenticated", path);
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            assertProblem(mvc.perform(get(path).cookie(login(user))).andReturn(), 403, "forbidden", path);
        }
        // an inbound agent without REACTIVATION_APPROVER, although verified
        assertProblem(mvc.perform(get(path).cookie(login("T-IN"))).andReturn(), 403, "forbidden", path);
        // the approver without a valid check of their own
        assertProblem(mvc.perform(get(path).cookie(login("T-IN-REACT"))).andReturn(), 403, "verification-required",
                path);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> eligibility(Cookie session, long customerId) throws Exception {
        MvcResult result = mvc.perform(get(path(customerId)).cookie(session)).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private static String path(long customerId) {
        return "/api/v1/agent/customers/" + customerId + "/reactivation-eligibility";
    }

    /** A request row as the given status (terminal rows with times), created at {@code createdAt}. */
    private String insertRequest(String accountRef, String username, long checkId, String status, Instant createdAt) {
        String reference = "REA-20300115-" + (990000 + REFS.incrementAndGet());
        boolean started = List.of("ACTIVE", "EXPIRING", "EXPIRED", "ENDED").contains(status);
        jdbc.update("""
                INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours,
                                                   reason_text, identity_check_id, requested_by_user_id, status,
                                                   activated_at, expires_at, created_at, updated_at)
                SELECT :ref, a.id, 'NON_PAYMENT', JSON_ARRAY('+919900000006'), 24, 'Test reactivation row',
                       :check, u.id, :status, :activated, :expires, :t, :t
                FROM accounts a JOIN users u ON u.username = :username
                WHERE a.account_ref = :account
                """, new MapSqlParameterSource()
                .addValue("ref", reference)
                .addValue("check", checkId)
                .addValue("status", status)
                .addValue("activated", started ? utc(createdAt) : null)
                .addValue("expires", started ? utc(createdAt.plus(Duration.ofHours(24))) : null)
                .addValue("t", utc(createdAt))
                .addValue("username", username)
                .addValue("account", accountRef));
        return reference;
    }

    /** A KBA check of {@code username} for the customer, VERIFIED now for 30 minutes. */
    private long verify(String username, long customerId) {
        Instant now = InboundTestClock.CLOCK.instant();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', 'VERIFIED', 1, :t, :validUntil, :t, :t)
                """, new MapSqlParameterSource()
                .addValue("agent", userId(username))
                .addValue("customer", customerId)
                .addValue("t", utc(now))
                .addValue("validUntil", utc(now.plus(Duration.ofMinutes(30)))), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    private long customerId(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                Long.class);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = :u", Map.of("u", username), Long.class);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Logs in like the SPA from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.37." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
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
        List<Cookie> sessions = new ArrayList<>();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("SESSION")) {
                sessions.add(new Cookie("SESSION", c.getValue()));
            }
        }
        assertThat(sessions).hasSize(1);
        return sessions.get(0);
    }
}
