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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Central verified scope and record-access log (design 03-inbound backend R5, R6, §4, §9; INB-01.2, INB-01.4, BR-10).
 * Driven through {@link VerifiedProbeController} (a read and an idempotent create under
 * {@code /agent/customers/{customerId}/**}); the feature ITs add their own routes. VERIFIED checks are inserted directly,
 * relative to {@link InboundTestClock#T0}. Shares the inbound context (same clock, logging property and import).
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class VerifiedCustomerAccessIT extends AbstractMySqlIT {

    private static final String XSRF = "verified-xsrf-token";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private long c6;
    private long c7;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        cleanTables();
        c6 = customerId("CUST-T-0006");
        c7 = customerId("CUST-T-0007");
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", Map.of("t", utc(T0.minus(Duration.ofDays(2)))));
        jdbc.update("DELETE FROM idempotency_keys WHERE operation IN (:ops)",
                Map.of("ops", List.of(VerifiedProbeController.OPERATION, "CASE_CREATE_ONBEHALF")));
    }

    @Test
    void withoutACheckEveryVerifiedRouteIsVerificationRequiredAlsoForAnUnknownId() throws Exception {
        Cookie agent = login("T-IN");
        int before = VerifiedProbeController.CREATES.get();

        assertProblem(read(agent, c6).andReturn(), 403, "verification-required", probe(c6));
        assertProblem(create(agent, c6, "k-none", "x").andReturn(), 403, "verification-required", probe(c6));
        long unknown = 987_654_321L;
        MvcResult unknownId = read(agent, unknown).andReturn();
        assertProblem(unknownId, 403, "verification-required", probe(unknown));

        Map<String, Object> denied = jdbc.queryForMap("""
                SELECT actor_username, outcome, details FROM audit_events
                WHERE action = 'ACCESS_DENIED' AND correlation_id = :cid
                """, Map.of("cid", unknownId.getResponse().getHeader("X-Correlation-Id")));
        assertThat(denied.get("actor_username")).isEqualTo("T-IN");
        assertThat(denied.get("outcome")).isEqualTo("DENIED");
        assertThat(json((String) denied.get("details")))
                .containsEntry("reason", "verification_required")
                .containsEntry("path", probe(unknown));
        assertThat(VerifiedProbeController.CREATES.get()).isEqualTo(before);
    }

    @Test
    void aVerifiedCheckGrantsThirtyMinutesAndIsNeverExtended() throws Exception {
        Cookie agent = login("T-IN");
        long check = insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));

        Map<String, Object> body = json(read(agent, c6).andExpect(status().isOk()).andReturn());

        assertThat(body).containsEntry("customerId", (int) c6).containsEntry("checkId", (int) check)
                .containsEntry("accountId", (int) accountIdOf(c6))
                .containsEntry("agentUserId", (int) userId("T-IN"));
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(29)));
        read(agent, c6).andExpect(status().isOk());
        assertThat(validUntil(check)).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(30))); // valid_until is exclusive
        assertProblem(read(agent, c6).andReturn(), 403, "verification-required", probe(c6));
    }

    @Test
    void aCheckCoversOnlyItsAgentAndItsCustomer() throws Exception {
        insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));

        assertProblem(read(login("T-IN-REACT"), c6).andReturn(), 403, "verification-required", probe(c6));
        assertProblem(read(login("T-IN"), c7).andReturn(), 403, "verification-required", probe(c7));
    }

    @Test
    void onlyAValidVerifiedCheckCounts() throws Exception {
        Cookie agent = login("T-IN");
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', 'FAILED', 1, :t, :t)
                """, Map.of("agent", userId("T-IN"), "customer", c6, "t", utc(T0)));
        long superseded = insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));
        jdbc.update("UPDATE identity_checks SET status = 'SUPERSEDED' WHERE id = :id", Map.of("id", superseded));

        assertProblem(read(agent, c6).andReturn(), 403, "verification-required", probe(c6));
    }

    @Test
    void rolesWithoutCustomerLookupAreForbiddenNotVerificationRequired() throws Exception {
        insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));
        int before = VerifiedProbeController.CREATES.get();

        mvc.perform(get(probe(c6))).andExpect(status().isUnauthorized());
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(user);
            assertProblem(read(session, c6).andReturn(), 403, "forbidden", probe(c6));
            assertProblem(create(session, c6, "k-" + user, "x").andReturn(), 403, "forbidden", probe(c6));
        }
        assertThat(VerifiedProbeController.CREATES.get()).isEqualTo(before);
    }

    @Test
    void identityCheckRoutesAreNotBehindTheVerifiedScope() throws Exception {
        Cookie agent = login("T-IN");

        assertProblem(mvc.perform(get("/api/v1/agent/customers/" + c6 + "/identity-checks/current").cookie(agent))
                .andReturn(), 404, "not-found", "/api/v1/agent/customers/" + c6 + "/identity-checks/current");
    }

    @Test
    void fiveParallelFirstReadsWriteOneRecordAccessedRow() throws Exception {
        long check = insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));

        List<Integer> statuses = parallel(5, "T-IN", session -> read(session, c6));

        assertThat(statuses).containsOnly(200).hasSize(5);
        List<Map<String, Object>> rows = accessedRows(check);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor_username")).isEqualTo("T-IN");
        assertThat(rows.get(0).get("entity_type")).isEqualTo("CUSTOMER");
        assertThat(rows.get(0).get("entity_id")).isEqualTo("CUST-T-0006");
        assertThat(rows.get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(json((String) rows.get(0).get("details")))
                .isEqualTo(Map.of("checkId", (int) check, "path", probe(c6)));
        assertThat(jdbc.queryForObject("SELECT access_logged_at FROM identity_checks WHERE id = :id",
                Map.of("id", check), LocalDateTime.class)).isEqualTo(utc(T0));

        read(login("T-IN"), c6).andExpect(status().isOk());
        assertThat(accessedRows(check)).hasSize(1);

        long next = insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(40)));
        jdbc.update("UPDATE identity_checks SET status = 'SUPERSEDED' WHERE id = :id", Map.of("id", check));
        read(login("T-IN"), c6).andExpect(status().isOk());
        assertThat(accessedRows(next)).hasSize(1);
    }

    @Test
    void aWriteDoesNotLogTheRecordAccess() throws Exception {
        long check = insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));

        create(login("T-IN"), c6, "k-write", "x").andExpect(status().isCreated());

        assertThat(accessedRows(check)).isEmpty();
    }

    /** Through the real idempotent create I12 (R12), so the interceptor-before-idempotency order is the shipped one. */
    @Test
    void anIdempotentReplayOfCreateOnBehalfAfterExpiryIsVerificationRequired() throws Exception {
        Cookie agent = login("T-IN");
        insertVerified("T-IN", c6, T0.plus(Duration.ofMinutes(30)));
        String path = "/api/v1/agent/customers/" + c6 + "/cases";

        Map<String, Object> first = json(createCase(agent, path, "k-replay").andExpect(status().isCreated())
                .andReturn());
        Map<String, Object> replay = json(createCase(agent, path, "k-replay").andExpect(status().isCreated())
                .andReturn());
        assertThat(replay).isEqualTo(first);

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(30)));
        assertProblem(createCase(agent, path, "k-replay").andReturn(), 403, "verification-required", path);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cases WHERE subject = 'Replay probe request'",
                Map.of(), Long.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private interface Call {
        ResultActions perform(Cookie session) throws Exception;
    }

    private List<Integer> parallel(int n, String username, Call call) throws Exception {
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sessions.add(login(username));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Cookie session = sessions.get(i);
                Callable<Integer> task = () -> {
                    go.await();
                    return call.perform(session).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions read(Cookie session, long customerId) throws Exception {
        return mvc.perform(get(probe(customerId)).cookie(session));
    }

    private ResultActions create(Cookie session, long customerId, String key, String name) throws Exception {
        return mvc.perform(post(probe(customerId))
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions createCase(Cookie session, String path, String key) throws Exception {
        return mvc.perform(post(path)
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("categoryCode", "OTHER",
                        "subject", "Replay probe request", "customerStatement", "The customer asks for a replay probe.",
                        "preferredContact", "SMS"))));
    }

    private static String probe(long customerId) {
        return VerifiedProbeController.PATH.replace("{customerId}", String.valueOf(customerId));
    }

    /** A KBA check of {@code username} for the customer, VERIFIED at T0 until {@code validUntil}. */
    private long insertVerified(String username, long customerId, Instant validUntil) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', 'VERIFIED', 1, :t, :validUntil, :t, :t)
                """, new MapSqlParameterSource()
                .addValue("agent", userId(username))
                .addValue("customer", customerId)
                .addValue("t", utc(InboundTestClock.CLOCK.instant()))
                .addValue("validUntil", utc(validUntil)), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    private Instant validUntil(long check) {
        return jdbc.queryForObject("SELECT valid_until FROM identity_checks WHERE id = :id", Map.of("id", check),
                LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private List<Map<String, Object>> accessedRows(long check) {
        return jdbc.queryForList("""
                SELECT actor_username, entity_type, entity_id, outcome, details FROM audit_events
                WHERE action = 'CUSTOMER_RECORD_ACCESSED'
                  AND JSON_EXTRACT(details, '$.checkId') = :check
                """, Map.of("check", check));
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return json(result.getResponse().getContentAsString());
    }

    private Map<String, Object> json(String raw) throws Exception {
        return objectMapper.readValue(raw, new TypeReference<>() {});
    }

    private long customerId(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                Long.class);
    }

    private long accountIdOf(long customerId) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE customer_id = :c", Map.of("c", customerId),
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
        String ip = "10.33." + (n / 250) + "." + (n % 250 + 1);
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
