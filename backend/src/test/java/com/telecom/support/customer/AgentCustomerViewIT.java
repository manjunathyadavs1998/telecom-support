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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Verified customer view I5–I8 (design 03-inbound backend R5, R7, §4, §5, §9; INB-01.3, NFR-PRIV, BR-05/06). The
 * shared inbound clock is set to the real time here because the 02 test data (invoices, payments) is relative to the
 * real date; VERIFIED checks are inserted directly. Test data: CUST-T-0001 (ACTIVE, 2 subscriptions, INV-T-0001..0003,
 * payments 900001 at now-118 d, 900002/900003 at now-28/27 d), CUST-T-0006 (SUSPENDED NON_PAYMENT, verified email).
 * Other tasks may add payments "now", so lists are checked by the test-data references.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class AgentCustomerViewIT extends AbstractMySqlIT {

    private static final String XSRF = "view-xsrf-token";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final List<String> ROUTES = List.of("profile", "subscriptions", "invoices", "payments");

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final List<Long> checks = new ArrayList<>();
    private long c1;
    private long c6;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        c1 = customerId("CUST-T-0001");
        c6 = customerId("CUST-T-0006");
    }

    @AfterEach
    void cleanUp() {
        if (!checks.isEmpty()) {
            jdbc.update("DELETE FROM identity_checks WHERE id IN (:ids)", Map.of("ids", checks));
            checks.clear();
        }
        InboundTestClock.CLOCK.set(InboundTestClock.T0);
    }

    // ------------------------------------------------------------------ I5

    @Test
    void profileShowsIdentityStatusesSuspensionAndContactsButNoBillingAddress() throws Exception {
        Cookie agent = login("T-IN");
        long check = verify("T-IN", c6);

        Map<String, Object> profile = json(view(agent, c6, "profile").andExpect(status().isOk()).andReturn());

        assertThat(profile).containsOnlyKeys("customerId", "customerRef", "accountRef", "fullName", "aliasName",
                "customerStatus", "accountStatus", "suspension", "primaryMsisdn", "email", "emailVerified",
                "secondaryMsisdn", "secondaryMsisdnVerified");
        assertThat(profile).containsEntry("customerId", (int) c6).containsEntry("customerRef", "CUST-T-0006")
                .containsEntry("accountRef", "ACC-T-0006").containsEntry("fullName", "Test Customer Suspended")
                .containsEntry("customerStatus", "ACTIVE").containsEntry("accountStatus", "SUSPENDED")
                .containsEntry("primaryMsisdn", "+919900000006")
                .containsEntry("email", "test.customer6@example.test").containsEntry("emailVerified", true)
                .containsEntry("secondaryMsisdn", null).containsEntry("secondaryMsisdnVerified", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> suspension = (Map<String, Object>) profile.get("suspension");
        assertThat(suspension).containsOnlyKeys("reason", "since").containsEntry("reason", "NON_PAYMENT");
        Instant since = jdbc.queryForObject("SELECT suspended_at FROM accounts WHERE account_ref = 'ACC-T-0006'",
                Map.of(), LocalDateTime.class).toInstant(ZoneOffset.UTC);
        assertThat(Instant.parse((String) suspension.get("since"))).isEqualTo(since);
        String raw = view(agent, c6, "profile").andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("billingAddress", "560001", "Test Avenue");

        assertThat(accessedRows(check)).isEqualTo(1);
    }

    @Test
    void profileOfAnActiveAccountHasNoSuspension() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1);

        Map<String, Object> profile = json(view(agent, c1, "profile").andExpect(status().isOk()).andReturn());

        assertThat(profile).containsEntry("accountStatus", "ACTIVE").containsEntry("suspension", null)
                .containsEntry("primaryMsisdn", "+919900000001");
    }

    // ------------------------------------------------------------------ I6

    @Test
    void subscriptionsArePrimaryFirstWithPlans() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1);

        List<Map<String, Object>> subs = list(view(agent, c1, "subscriptions").andExpect(status().isOk()).andReturn());

        assertThat(subs).extracting(s -> s.get("msisdn")).containsExactly("+919900000001", "+919900000011");
        assertThat(subs.get(0)).containsEntry("primary", true).containsKeys("id", "status", "billingCycleDay", "plan",
                "pendingPlanChange");
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) subs.get(0).get("plan");
        assertThat(plan.get("code")).isEqualTo("POST_399");
        assertThat(plan).containsKeys("name", "planType", "monthlyRental", "benefits");
    }

    // ------------------------------------------------------------------ I7, I8

    @Test
    void invoicesAreTheAccountsLatestOptionallyByStatus() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1);

        List<Map<String, Object>> all = list(view(agent, c1, "invoices").andExpect(status().isOk()).andReturn());
        assertThat(all).extracting(i -> i.get("invoiceRef")).containsSubsequence("INV-T-0003", "INV-T-0002",
                "INV-T-0001").doesNotContain("INV-T-0101", "INV-T-0102");
        assertThat(all).hasSizeLessThanOrEqualTo(12);
        assertThat(list(view(agent, c1, "invoices?status=PAID").andExpect(status().isOk()).andReturn()))
                .extracting(i -> i.get("status")).containsOnly("PAID");
        assertProblem(view(agent, c1, "invoices?status=LATE").andReturn(), 400, "bad-request",
                path(c1, "invoices"));
    }

    @Test
    void paymentsUseTheThreeMonthWindowAndPage() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1);
        LocalDate today = LocalDate.now(IST);

        Map<String, Object> window = json(view(agent, c1, "payments").andExpect(status().isOk()).andReturn());
        assertThat(references(window)).contains("900002", "900003").doesNotContain("900001", "900004");
        assertThat(window).containsEntry("page", 0).containsEntry("size", 20);

        Map<String, Object> older = json(view(agent, c1, "payments?from=" + today.minusDays(130) + "&to="
                + today.minusDays(1)).andExpect(status().isOk()).andReturn());
        assertThat(references(older)).containsExactly("900003", "900002", "900001");

        Map<String, Object> toOnly = json(view(agent, c1, "payments?to=" + today.minusDays(100))
                .andExpect(status().isOk()).andReturn());
        assertThat(references(toOnly)).containsExactly("900001");

        Map<String, Object> asc = json(view(agent, c1, "payments?from=" + today.minusDays(130) + "&to="
                + today.minusDays(1) + "&sort=initiatedAt,asc&size=1&page=1").andExpect(status().isOk()).andReturn());
        assertThat(references(asc)).containsExactly("900002");
        assertThat(asc).containsEntry("totalItems", 3).containsEntry("totalPages", 3).containsEntry("size", 1);

        assertProblem(view(agent, c1, "payments?sort=amount,desc").andReturn(), 400, "bad-request",
                path(c1, "payments"));
        assertProblem(view(agent, c1, "payments?from=" + today.minusDays(400) + "&to=" + today).andReturn(), 400,
                "bad-request", path(c1, "payments"));
        assertThat(view(agent, c1, "payments?size=101").andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ scope and authorization

    @Test
    void everyViewRouteWithoutAValidCheckIsVerificationRequired() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6); // a check for another customer does not help

        for (String route : ROUTES) {
            assertProblem(view(agent, c1, route).andReturn(), 403, "verification-required", path(c1, route));
        }
    }

    @Test
    void anonymousIs401AndRolesWithoutCustomerLookupAre403Forbidden() throws Exception {
        verify("T-IN", c1);
        for (String route : ROUTES) {
            assertProblem(mvc.perform(get(path(c1, route))).andReturn(), 401, "unauthenticated", path(c1, route));
        }
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(user);
            for (String route : ROUTES) {
                assertProblem(view(session, c1, route).andReturn(), 403, "forbidden", path(c1, route));
            }
        }
    }

    @Test
    void allViewReadsUnderOneCheckLogTheAccessOnce() throws Exception {
        Cookie agent = login("T-IN");
        long check = verify("T-IN", c1);

        for (String route : ROUTES) {
            view(agent, c1, route).andExpect(status().isOk());
        }

        assertThat(accessedRows(check)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions view(Cookie session, long customerId, String route)
            throws Exception {
        return mvc.perform(get(path(customerId, "") + route).cookie(session));
    }

    private static String path(long customerId, String route) {
        return "/api/v1/agent/customers/" + customerId + "/" + route;
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
        long id = keys.getKey().longValue();
        checks.add(id);
        return id;
    }

    private long accessedRows(long check) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'CUSTOMER_RECORD_ACCESSED' AND JSON_EXTRACT(details, '$.checkId') = :check
                """, Map.of("check", check), Long.class);
    }

    /** The sequence part of each payment reference ({@code PAY-yyyymmdd-NNNNNN}), in list order. */
    @SuppressWarnings("unchecked")
    private static List<String> references(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .map(p -> (String) p.get("reference"))
                .map(ref -> ref.substring(ref.lastIndexOf('-') + 1))
                .toList();
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> list(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
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
        String ip = "10.34." + (n / 250) + "." + (n % 250 + 1);
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
