package com.telecom.support.reactivation;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.customer.InboundTestClock;
import com.telecom.support.customer.VerifiedProbeController;
import com.telecom.support.identity.AppUserDetailsService;
import com.telecom.support.identity.Permissions;
import com.telecom.support.identity.TestPrincipals;
import com.telecom.support.reactivation.MockProvisioningClient.Mode;
import com.telecom.support.reactivation.MockProvisioningClient.Operation;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Create I14 and history I15 (design 03-inbound backend R14, R15, §3.1, §4, §7, §9; INB-04.1..04.4/04.6/04.7, NFR-REL,
 * XC-06/07). Runs at the shared inbound clock's T0 with the in-memory provisioning mock. VERIFIED checks, extra
 * suspended customers ({@code CUST-T-R…}) and request rows are created by the test and removed afterwards; audit rows
 * stay and are matched by this test's references or correlation ids. Agents act as {@code T-IN-REACT} (the seeded
 * approver) and, for the two-agent race, {@code T-IN} plus {@code service.reactivate} ({@link TestPrincipals#with}).
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class ReactivationCreateIT extends AbstractMySqlIT {

    private static final String XSRF = "reactivation-create-xsrf";
    private static final Instant T0 = InboundTestClock.T0;
    private static final Instant TEST_ROWS_FROM = T0.minus(Duration.ofDays(200));
    private static final String REASON = "Customer promised to pay today and needs service to pay online.";
    private static final AtomicInteger IPS = new AtomicInteger();
    /** The {@code ReactivationProblem} extensions. */
    private static final Set<String> EXTENSIONS = Set.of("reasons", "reference", "reactivationStatus");

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AppUserDetailsService userDetails;

    @Autowired
    MockProvisioningClient provisioning;

    private long c6;
    private long c7;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        provisioning.reset();
        provisioning.setTimeoutDelay(Duration.ofMillis(50));
        cleanTables();
        c6 = customerId("CUST-T-0006");
        c7 = customerId("CUST-T-0007");
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
        jdbc.update("UPDATE reactivation_policies SET max_hours = 24 WHERE suspension_reason = 'NON_PAYMENT'",
                Map.of());
        provisioning.reset();
        InboundTestClock.CLOCK.set(T0);
    }

    private void cleanTables() {
        Map<String, Object> from = Map.of("t", utc(TEST_ROWS_FROM));
        jdbc.update("DELETE FROM notifications WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM reactivation_requests WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM idempotency_keys WHERE operation = 'REACTIVATION_CREATE'", Map.of());
        jdbc.update("""
                DELETE s FROM subscriptions s JOIN accounts a ON a.id = s.account_id
                WHERE a.account_ref LIKE 'ACC-T-R%'
                """, Map.of());
        jdbc.update("DELETE FROM accounts WHERE account_ref LIKE 'ACC-T-R%'", Map.of());
        jdbc.update("DELETE FROM customers WHERE customer_ref LIKE 'CUST-T-R%'", Map.of());
    }

    // ------------------------------------------------------------------ I14

    @Test
    void createsAnActiveReactivationAfterTheProviderConfirmsAndNotifiesAndAudits() throws Exception {
        long check = verify("T-IN-REACT", c6);
        Map<String, Object> request = body(4);
        request.put("approvalReference", "APR-77");
        request.put("accountId", 1);       // ignored
        request.put("status", "ACTIVE");   // ignored

        MvcResult result = create(approver(), c6, key(), request).andExpect(status().isCreated()).andReturn();
        Map<String, Object> created = map(result);

        assertThat(created).containsOnlyKeys("reference", "status", "suspensionReason", "msisdns", "requestedHours",
                "reason", "approvalReference", "requestedByName", "failureReason", "createdAt", "activatedAt",
                "expiresAt", "endedAt", "needsAttention", "endReason");
        String reference = (String) created.get("reference");
        assertThat(reference).matches("REA-20300115-\\d{6,}");
        assertThat(created).containsEntry("status", "ACTIVE").containsEntry("suspensionReason", "NON_PAYMENT")
                .containsEntry("msisdns", List.of("+919900000006", "+919900000016"))
                .containsEntry("requestedHours", 4).containsEntry("reason", REASON)
                .containsEntry("approvalReference", "APR-77").containsEntry("requestedByName", "Test Inbound Approver")
                .containsEntry("failureReason", null).containsEntry("needsAttention", false)
                .containsEntry("endReason", null).containsEntry("endedAt", null);
        assertThat(Instant.parse((String) created.get("activatedAt"))).isEqualTo(T0);
        assertThat(Instant.parse((String) created.get("expiresAt"))).isEqualTo(T0.plus(Duration.ofHours(4)));

        Map<String, Object> row = row(reference);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("provider_ref", "PRV-" + reference)
                .containsEntry("identity_check_id", check).containsEntry("requester", "T-IN-REACT")
                .containsEntry("account_ref", "ACC-T-0006");
        assertThat(row.get("next_attempt_at")).isNull();

        List<MockProvisioningClient.Call> calls = provisioning.callsFor(reference);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).operation()).isEqualTo(Operation.RESTORE);
        assertThat(calls.get(0).msisdns()).containsExactly("+919900000006", "+919900000016");
        assertThat(calls.get(0).until()).isEqualTo(T0.plus(Duration.ofHours(4)));

        assertThat(jdbc.queryForList("""
                SELECT channel, template, destination, status FROM notifications WHERE source_ref = :ref
                ORDER BY channel
                """, Map.of("ref", reference))).containsExactly(
                Map.of("channel", "EMAIL", "template", "REACTIVATION_STARTED",
                        "destination", "test.customer6@example.test", "status", "PENDING"),
                Map.of("channel", "SMS", "template", "REACTIVATION_STARTED", "destination", "+919900000006",
                        "status", "PENDING"));

        Map<String, Object> requested = audit("REACTIVATION_REQUESTED", reference);
        assertThat(requested).containsEntry("actor_username", "T-IN-REACT").containsEntry("outcome", "SUCCESS")
                .containsEntry("entity_type", "REACTIVATION");
        assertThat(map((String) requested.get("details"))).containsEntry("accountRef", "ACC-T-0006")
                .containsEntry("suspensionReason", "NON_PAYMENT").containsEntry("hours", 4)
                .containsEntry("reason", REASON).containsEntry("approvalReference", "APR-77")
                .containsEntry("checkId", (int) check);
        Map<String, Object> activated = audit("REACTIVATION_ACTIVATED", reference);
        assertThat(activated).containsEntry("actor_username", "T-IN-REACT").containsEntry("outcome", "SUCCESS");
        assertThat(map((String) activated.get("details"))).containsEntry("from", "PENDING")
                .containsEntry("to", "ACTIVE").containsEntry("source", "request");

        // billing owns accounts and subscriptions (R15)
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_ref = 'ACC-T-0006'", Map.of(),
                String.class)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForList("""
                SELECT s.status FROM subscriptions s JOIN accounts a ON a.id = s.account_id
                WHERE a.account_ref = 'ACC-T-0006'
                """, Map.of(), String.class)).containsOnly("SUSPENDED");
    }

    @Test
    void hoursDefaultToThePolicyAndAreBoundedBy1To24AndThePolicy() throws Exception {
        verify("T-IN-REACT", c6);
        for (int hours : List.of(0, 25)) {
            assertProblem(create(approver(), c6, key(), body(hours)).andReturn(), 400, "validation", path(c6));
        }
        Map<String, Object> shortReason = body(null);
        shortReason.put("reason", "too short");
        assertProblem(create(approver(), c6, key(), shortReason).andReturn(), 400, "validation", path(c6));
        assertProblem(create(approver(), c6, null, body(null)).andReturn(), 400, "bad-request", path(c6));

        jdbc.update("UPDATE reactivation_policies SET max_hours = 6 WHERE suspension_reason = 'NON_PAYMENT'", Map.of());
        MvcResult over = create(approver(), c6, key(), body(8)).andReturn();
        assertProblem(over, 422, "business-rule", path(c6), EXTENSIONS);
        assertThat(map(over)).containsEntry("reasons", List.of("HOURS_EXCEED_POLICY"));
        assertThat(map(rejection(over))).containsEntry("reasons", List.of("HOURS_EXCEED_POLICY"));
        assertThat(requestsOf("ACC-T-0006")).isZero();

        Map<String, Object> created = map(create(approver(), c6, key(), body(null)).andExpect(status().isCreated())
                .andReturn());
        assertThat(created).containsEntry("requestedHours", 6).containsEntry("approvalReference", null);
        assertThat(Instant.parse((String) created.get("expiresAt"))).isEqualTo(T0.plus(Duration.ofHours(6)));
    }

    @Test
    void anIneligibleAccountIs422WithItsReasonsAndAnIndependentRejectionAudit() throws Exception {
        verify("T-IN-REACT", c7);

        MvcResult result = create(approver(), c7, key(), body(4)).andReturn();

        assertProblem(result, 422, "business-rule", path(c7), EXTENSIONS);
        Map<String, Object> problem = map(result);
        assertThat(problem).containsEntry("reasons", List.of("PROHIBITED_REASON", "REASON_NOT_ELIGIBLE"))
                .doesNotContainKey("reference");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, outcome, entity_type, entity_id, details FROM audit_events
                WHERE action = 'REACTIVATION_REJECTED' AND correlation_id = :cid
                """, Map.of("cid", result.getResponse().getHeader("X-Correlation-Id")));
        assertThat(audit).containsEntry("actor_username", "T-IN-REACT").containsEntry("outcome", "FAILURE")
                .containsEntry("entity_type", "ACCOUNT").containsEntry("entity_id", "ACC-T-0007");
        assertThat(requestsOf("ACC-T-0007")).isZero();
        assertThat(provisioning.calls()).isEmpty();
    }

    @Test
    void twoAgentsInParallelOnOneAccountGetOneRequestAndConflicts() throws Exception {
        verify("T-IN-REACT", c6);
        verify("T-IN", c6);
        Authentication second = TestPrincipals.with(userDetails, "T-IN", Permissions.SERVICE_REACTIVATE);
        List<Authentication> agents = List.of(approverPrincipal(), second);

        List<Integer> statuses = parallel(6, i -> create(authentication(agents.get(i % 2)), c6, key(), body(4)));

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(requestsOf("ACC-T-0006")).isEqualTo(1);
        assertThat(provisioning.calls()).hasSize(1);
    }

    @Test
    void oneAgentInParallelOnElevenAccountsIsLimitedToTen() throws Exception {
        List<Long> customers = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            long customerId = newSuspendedCustomer(i);
            verify("T-IN-REACT", customerId);
            customers.add(customerId);
        }

        List<MvcResult> results = parallelResults(11,
                i -> create(approver(), customers.get(i), key(), body(2)));

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(1);
        MvcResult refused = results.stream().filter(r -> r.getResponse().getStatus() == 422).findFirst().orElseThrow();
        assertThat(map(refused)).containsEntry("reasons", List.of("AGENT_LIMIT_REACHED"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reactivation_requests r JOIN accounts a ON a.id = r.account_id
                WHERE a.account_ref LIKE 'ACC-T-R%' AND r.status = 'ACTIVE'
                """, Map.of(), Long.class)).isEqualTo(10);
    }

    @Test
    void aReplayReturnsTheSameReactivationAndTheHashCoversBodyAndCustomer() throws Exception {
        verify("T-IN-REACT", c6);
        long other = newSuspendedCustomer(1);
        verify("T-IN-REACT", other);
        String key = key();

        Map<String, Object> first = map(create(approver(), c6, key, body(4)).andExpect(status().isCreated())
                .andReturn());
        Map<String, Object> replay = map(create(approver(), c6, key, body(4)).andExpect(status().isCreated())
                .andReturn());

        assertThat(replay).isEqualTo(first);
        assertThat(requestsOf("ACC-T-0006")).isEqualTo(1);
        assertThat(provisioning.calls()).hasSize(1);
        assertProblem(create(approver(), c6, key, body(5)).andReturn(), 422, "idempotency-mismatch", path(c6));
        assertProblem(create(approver(), other, key, body(4)).andReturn(), 422, "idempotency-mismatch",
                path(other));
    }

    @Test
    void aReplayAfterTheCheckExpiredIsVerificationRequired() throws Exception {
        verify("T-IN-REACT", c6);
        String key = key();
        create(approver(), c6, key, body(4)).andExpect(status().isCreated());

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(30)));
        assertProblem(create(approver(), c6, key, body(4)).andReturn(), 403, "verification-required", path(c6));
    }

    @Test
    void aProviderRejectionFailsTheRequestAndReleasesTheKey() throws Exception {
        verify("T-IN-REACT", c6);
        String key = key();
        provisioning.setMode(Mode.REJECT);

        MvcResult rejected = create(approver(), c6, key, body(4)).andReturn();

        assertProblem(rejected, 422, "business-rule", path(c6), EXTENSIONS);
        Map<String, Object> problem = map(rejected);
        String reference = (String) problem.get("reference");
        assertThat(problem).containsEntry("reactivationStatus", "FAILED").doesNotContainKey("reasons");
        assertThat(row(reference)).containsEntry("status", "FAILED").containsEntry("failure_reason",
                "provider_rejected");
        assertThat(map((String) audit("REACTIVATION_FAILED", reference).get("details")))
                .containsEntry("reason", "provider_rejected");
        assertThat(audit("REACTIVATION_FAILED", reference)).containsEntry("outcome", "FAILURE");
        assertThat(notificationsOf(reference)).isZero();

        provisioning.setMode(Mode.NORMAL); // a FAILED request blocks nothing; the same key may be used again
        Map<String, Object> retried = map(create(approver(), c6, key, body(4)).andExpect(status().isCreated())
                .andReturn());
        assertThat(retried.get("reference")).isNotEqualTo(reference);
        assertThat(retried).containsEntry("status", "ACTIVE");
    }

    @Test
    void anUnavailableProviderIs503FailedAndARetryIsAllowed() throws Exception {
        verify("T-IN-REACT", c6);
        String key = key();
        provisioning.setMode(Mode.UNAVAILABLE);

        MvcResult unavailable = create(approver(), c6, key, body(4)).andReturn();

        assertProblem(unavailable, 503, "dependency-unavailable", path(c6), EXTENSIONS);
        Map<String, Object> problem = map(unavailable);
        String reference = (String) problem.get("reference");
        assertThat(problem).containsEntry("reactivationStatus", "FAILED");
        assertThat(row(reference)).containsEntry("status", "FAILED").containsEntry("failure_reason",
                "provider_unavailable");
        assertThat(map((String) audit("REACTIVATION_FAILED", reference).get("details")))
                .containsEntry("reason", "provider_unavailable");

        provisioning.setMode(Mode.NORMAL);
        assertThat(map(create(approver(), c6, key, body(4)).andExpect(status().isCreated()).andReturn()))
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void aProviderTimeoutLeavesThePendingRequestReconcilableAndARetryConflicts() throws Exception {
        verify("T-IN-REACT", c6);
        String key = key();
        provisioning.setMode(Mode.TIMEOUT);

        MvcResult timedOut = create(approver(), c6, key, body(4)).andReturn();

        assertProblem(timedOut, 503, "dependency-unavailable", path(c6), EXTENSIONS);
        Map<String, Object> problem = map(timedOut);
        String reference = (String) problem.get("reference");
        assertThat(problem).containsEntry("reactivationStatus", "PENDING");
        Map<String, Object> row = row(reference);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("failure_reason", "provider_unknown");
        assertThat(row.get("next_attempt_at")).isEqualTo(utc(T0.plus(Duration.ofMinutes(1))));
        assertThat(provisioning.statusOf(reference)).isPresent(); // the provider did get it: reconciled later
        assertThat(notificationsOf(reference)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE entity_id = :ref
                AND action IN ('REACTIVATION_ACTIVATED', 'REACTIVATION_FAILED')
                """, Map.of("ref", reference), Long.class)).isZero();

        provisioning.setMode(Mode.NORMAL);
        assertProblem(create(approver(), c6, key, body(4)).andReturn(), 409, "conflict", path(c6));
        assertThat(requestsOf("ACC-T-0006")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ I15

    @Test
    void historyListsTheAccountsRequestsNewestFirstInTheWindow() throws Exception {
        long check = verify("T-IN-REACT", c6);
        insertEnded("ACC-T-0006", check, T0.minus(Duration.ofDays(40)));
        insertEnded("ACC-T-0006", check, T0.minus(Duration.ofDays(120))); // outside the default 3 months
        String created = (String) map(create(approver(), c6, key(), body(3)).andExpect(status().isCreated())
                .andReturn()).get("reference");
        verify("T-IN", c6);

        // every inbound agent may read the history (customer.profile.read), not only approvers
        Map<String, Object> page = map(mvc.perform(get(path(c6)).cookie(login("T-IN"))).andExpect(status().isOk())
                .andReturn());
        assertThat(page).containsEntry("page", 0).containsEntry("size", 20).containsEntry("totalItems", 2)
                .containsEntry("totalPages", 1);
        List<Map<String, Object>> items = items(page);
        assertThat(items).extracting(i -> i.get("reference")).first().isEqualTo(created);
        assertThat(items.get(1)).containsEntry("status", "ENDED").containsEntry("endReason", "account_reinstated")
                .containsEntry("requestedByName", "Test Inbound Approver");

        Map<String, Object> all = map(mvc.perform(get(path(c6)).cookie(login("T-IN"))
                .param("from", "2029-09-01").param("size", "2").param("page", "1")).andExpect(status().isOk())
                .andReturn());
        assertThat(all).containsEntry("totalItems", 3).containsEntry("totalPages", 2);
        assertThat(items(all)).hasSize(1);

        assertProblem(mvc.perform(get(path(c6)).cookie(login("T-IN")).param("from", "2030-01-10")
                .param("to", "2030-01-01")).andReturn(), 400, "bad-request", path(c6));
        assertProblem(mvc.perform(get(path(c6)).cookie(login("T-IN")).param("size", "101")).andReturn(), 400,
                "validation", path(c6));
    }

    // ------------------------------------------------------------------ scope and authorization

    @Test
    void anonymousIs401OtherRolesAre403AndAVerifiedCheckIsRequired() throws Exception {
        verify("T-IN", c6);
        String path = path(c6);
        assertProblem(mvc.perform(withCsrf(post(path), key()).content(json(body(4)))).andReturn(), 401,
                "unauthenticated", path);
        assertProblem(mvc.perform(get(path)).andReturn(), 401, "unauthenticated", path);
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(user);
            assertProblem(mvc.perform(withCsrf(post(path).cookie(session), key()).content(json(body(4))))
                    .andReturn(), 403, "forbidden", path);
            assertProblem(mvc.perform(get(path).cookie(session)).andReturn(), 403, "forbidden", path);
        }
        // a verified inbound agent without REACTIVATION_APPROVER
        assertProblem(mvc.perform(withCsrf(post(path).cookie(login("T-IN")), key()).content(json(body(4))))
                .andReturn(), 403, "forbidden", path);
        // the approver without a check of their own
        Cookie approver = login("T-IN-REACT");
        assertProblem(mvc.perform(withCsrf(post(path).cookie(approver), key()).content(json(body(4))))
                .andReturn(), 403, "verification-required", path);
        assertProblem(mvc.perform(get(path).cookie(approver)).andReturn(), 403, "verification-required", path);
        // without CSRF
        verify("T-IN-REACT", c6);
        assertProblem(mvc.perform(post(path).cookie(approver).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key()).content(json(body(4)))).andReturn(), 403, "forbidden", path);
        assertThat(requestsOf("ACC-T-0006")).isZero();
        assertThat(provisioning.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private interface Call {
        ResultActions perform(int i) throws Exception;
    }

    private List<Integer> parallel(int n, Call call) throws Exception {
        return parallelResults(n, call).stream().map(r -> r.getResponse().getStatus()).toList();
    }

    /** {@code n} calls released together. */
    private List<MvcResult> parallelResults(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                Callable<MvcResult> task = () -> {
                    go.await();
                    return call.perform(k).andReturn();
                };
                futures.add(pool.submit(task));
            }
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Authentication approverPrincipal() {
        return TestPrincipals.with(userDetails, "T-IN-REACT");
    }

    private RequestPostProcessor approver() {
        return authentication(approverPrincipal());
    }

    private static Map<String, Object> body(Integer hours) {
        Map<String, Object> body = new HashMap<>();
        body.put("hours", hours);
        body.put("reason", REASON);
        return body;
    }

    private ResultActions create(RequestPostProcessor who, long customerId, String key, Map<String, Object> body)
            throws Exception {
        return mvc.perform(withCsrf(post(path(customerId)).with(who), key).content(json(body)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, String key) {
        MockHttpServletRequestBuilder r = request.cookie(new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON);
        return key == null ? r : r.header("Idempotency-Key", key);
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private static String path(long customerId) {
        return "/api/v1/agent/customers/" + customerId + "/reactivations";
    }

    private Map<String, Object> row(String reference) {
        return jdbc.queryForMap("""
                SELECT r.status, r.failure_reason, r.provider_ref, r.identity_check_id, r.next_attempt_at,
                       u.username AS requester, a.account_ref
                FROM reactivation_requests r
                JOIN users u ON u.id = r.requested_by_user_id
                JOIN accounts a ON a.id = r.account_id
                WHERE r.reference = :ref
                """, Map.of("ref", reference));
    }

    private Map<String, Object> audit(String action, String reference) {
        return jdbc.queryForMap("""
                SELECT actor_username, outcome, entity_type, details FROM audit_events
                WHERE action = :action AND entity_id = :ref
                """, Map.of("action", action, "ref", reference));
    }

    private String rejection(MvcResult result) {
        return jdbc.queryForObject("""
                SELECT details FROM audit_events WHERE action = 'REACTIVATION_REJECTED' AND correlation_id = :cid
                """, Map.of("cid", result.getResponse().getHeader("X-Correlation-Id")), String.class);
    }

    private long requestsOf(String accountRef) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM reactivation_requests r JOIN accounts a ON a.id = r.account_id
                WHERE a.account_ref = :ref AND r.created_at >= :t
                """, Map.of("ref", accountRef, "t", utc(TEST_ROWS_FROM)), Long.class);
    }

    private long notificationsOf(String reference) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE source_ref = :ref",
                Map.of("ref", reference), Long.class);
    }

    private void insertEnded(String accountRef, long checkId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours,
                                                   reason_text, identity_check_id, requested_by_user_id, status,
                                                   end_reason, activated_at, expires_at, ended_at, created_at,
                                                   updated_at)
                SELECT :ref, a.id, 'NON_PAYMENT', JSON_ARRAY('+919900000006'), 24, 'Earlier test reactivation',
                       :check, u.id, 'ENDED', 'account_reinstated', :t, :expires, :expires, :t, :t
                FROM accounts a JOIN users u ON u.username = 'T-IN-REACT'
                WHERE a.account_ref = :account
                """, new MapSqlParameterSource()
                .addValue("ref", "REA-20290101-" + (980000 + IPS.incrementAndGet()))
                .addValue("check", checkId)
                .addValue("t", utc(createdAt))
                .addValue("expires", utc(createdAt.plus(Duration.ofHours(24))))
                .addValue("account", accountRef));
    }

    /** {@code CUST-T-R0nn}: ACTIVE customer, account SUSPENDED NON_PAYMENT, one SUSPENDED primary, no email. */
    private long newSuspendedCustomer(int n) {
        String suffix = String.format("%03d", n);
        Instant now = InboundTestClock.CLOCK.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerRef", "CUST-T-R" + suffix)
                .addValue("accountRef", "ACC-T-R" + suffix)
                .addValue("msisdn", "+9199003000" + suffix.substring(1))
                .addValue("t", utc(now));
        jdbc.update("""
                INSERT INTO customers (customer_ref, full_name, billing_address, status, created_at, updated_at)
                VALUES (:customerRef, 'Test Reactivation Customer',
                        JSON_OBJECT('line1', '1 Test Road', 'city', 'Pune', 'state', 'MH', 'postalCode', '411002',
                                    'country', 'IN'), 'ACTIVE', :t, :t)
                """, params);
        jdbc.update("""
                INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, suspension_reason,
                                      suspended_at, created_at, updated_at)
                SELECT id, :accountRef, 3, 'SUSPENDED', 'NON_PAYMENT', :t, :t, :t FROM customers
                WHERE customer_ref = :customerRef
                """, params);
        jdbc.update("""
                INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at,
                                           updated_at)
                SELECT a.id, :msisdn, p.id, TRUE, 'SUSPENDED', :t, :t, :t
                FROM accounts a JOIN plans p ON p.code = 'PRE_199'
                WHERE a.account_ref = :accountRef
                """, params);
        return customerId("CUST-T-R" + suffix);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Map<String, Object> map(MvcResult result) throws Exception {
        return map(result.getResponse().getContentAsString());
    }

    private Map<String, Object> map(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
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
        String ip = "10.38." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", TEST_PASSWORD))))
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
