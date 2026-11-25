package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * E3/E4 contact verification end to end (design 02-customer backend R3, §3.2, §7, §8, §9; CUS-02.2, NFR-SEC, BR-10).
 * Time moves only through the {@link MutableClock}; nothing sleeps. DEBUG logging for the application packages makes
 * the "code never logged" check cover the debug paths too. Test data: R__test_users_02_customer.sql (customer 1 has a
 * verified email and no secondary number; customer 2 has neither; customer 3's account is CLOSED).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class ContactVerificationIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/contact-verifications";
    private static final String XSRF = "otp-xsrf-token";
    private static final Instant T0 = Instant.parse("2026-05-04T06:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final String C1 = "CUST-T-0001";
    private static final String C2 = "CUST-T-0002";

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockVerificationCodeSender sender;

    private List<Map<String, Object>> savedContacts;

    static Clock fixedClock() {
        return CLOCK;
    }

    @BeforeEach
    void reset() {
        CLOCK.set(T0);
        sender.setFailing(false);
        sender.clear();
        jdbc.update("DELETE FROM contact_verifications", Map.of());
        savedContacts = jdbc.queryForList("""
                SELECT id, email, email_verified_at, secondary_msisdn, secondary_verified_at FROM customers
                WHERE customer_ref IN ('CUST-T-0001', 'CUST-T-0002', 'CUST-T-0003')
                """, Map.of());
    }

    @AfterEach
    void restoreContacts() {
        sender.setFailing(false);
        for (Map<String, Object> c : savedContacts) {
            jdbc.update("""
                    UPDATE customers SET email = :email, email_verified_at = :email_verified_at,
                        secondary_msisdn = :secondary_msisdn, secondary_verified_at = :secondary_verified_at
                    WHERE id = :id
                    """, c);
        }
    }

    // ------------------------------------------------------------------ E3 + E4 happy paths

    @Test
    void startThenConfirmChangesTheEmailAndAudits() throws Exception {
        Cookie session = login("+919900000002");
        int versionBefore = version(C2);

        MvcResult started = start(session, "EMAIL", "  New.Mail@Example.TEST ").andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> body = json(started);
        assertThat(body).containsOnlyKeys("id", "channel", "maskedDestination", "expiresAt");
        assertThat(body.get("channel")).isEqualTo("EMAIL");
        assertThat(body.get("maskedDestination")).isEqualTo("ne***@ex***.test");
        assertThat(Instant.parse((String) body.get("expiresAt"))).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        long id = ((Number) body.get("id")).longValue();
        String code = sender.lastCode("new.mail@example.test").orElseThrow();
        assertThat(code).matches("\\d{6}");
        Map<String, Object> row = verification(id);
        assertThat(row.get("new_value")).isEqualTo("new.mail@example.test");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((String) row.get("code_hmac")).matches("[0-9a-f]{64}").doesNotContain(code);
        assertThat(email(C2)).isNull(); // nothing changes before the confirm
        Map<String, Object> startAudit = auditByCorrelation("CONTACT_VERIFICATION_STARTED", started);
        assertThat(startAudit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(details(startAudit)).isEqualTo(Map.of("channel", "EMAIL", "newValue", "ne***@ex***.test"));

        MvcResult confirmed = confirm(session, id, code).andExpect(status().isOk()).andReturn();

        Map<String, Object> profile = json(confirmed);
        assertThat(profile.get("customerRef")).isEqualTo(C2);
        assertThat(profile.get("email")).isEqualTo("new.mail@example.test");
        assertThat(profile.get("emailVerified")).isEqualTo(true);
        assertThat(profile.get("version")).isEqualTo(versionBefore + 1);
        assertThat(verification(id).get("status")).isEqualTo("VERIFIED");
        assertThat(verification(id).get("attempts")).isEqualTo(1);
        Map<String, Object> changeAudit = auditByCorrelation("PROFILE_CONTACT_CHANGED", confirmed);
        assertThat(changeAudit.get("entity_id")).isEqualTo(String.valueOf(customerId(C2)));
        Map<String, Object> expected = new HashMap<>();
        expected.put("channel", "EMAIL");
        expected.put("old", null);
        expected.put("new", "ne***@ex***.test");
        assertThat(details(changeAudit)).isEqualTo(expected);
    }

    @Test
    void secondaryNumberIsVerifiedAndMaskedToItsLastFourDigits() throws Exception {
        Cookie session = login("+919900000001");
        long id = startedId(start(session, "SECONDARY_MSISDN", "+919811112222").andExpect(status().isCreated())
                .andReturn(), "*********2222");

        Map<String, Object> profile = json(confirm(session, id, sender.lastCode("+919811112222").orElseThrow())
                .andExpect(status().isOk()).andReturn());

        assertThat(profile.get("secondaryMsisdn")).isEqualTo("+919811112222");
        assertThat(profile.get("secondaryMsisdnVerified")).isEqualTo(true);
        assertThat(profile.get("email")).isEqualTo("test.customer1@example.test"); // the other channel is untouched
    }

    @Test
    void invalidValuesAreValidationErrorsAndPrimaryNumberIsRejected() throws Exception {
        Cookie session = login("+919900000001");
        for (String[] bad : new String[][] {{"EMAIL", "not-an-email"}, {"EMAIL", "a@b"},
                {"SECONDARY_MSISDN", "9811112222"}, {"SECONDARY_MSISDN", "a@example.test"},
                {"EMAIL", "x".repeat(250) + "@e.in"}}) {
            Map<String, Object> problem = assertProblem(start(session, bad[0], bad[1]).andReturn(), 400, "validation",
                    PATH);
            assertThat(fields(problem)).as(bad[1]).containsExactly("value");
        }
        assertProblem(mvc.perform(post(PATH).cookie(session, new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"FAX\",\"value\":\"x@y.in\"}"))
                .andReturn(), 400, "bad-request", PATH);

        assertProblem(start(session, "SECONDARY_MSISDN", "+919900000001").andReturn(), 422, "business-rule", PATH);
        assertThat(count("SELECT COUNT(*) FROM contact_verifications")).isZero();
    }

    @Test
    void closedAccountCannotStart() throws Exception {
        assertProblem(start(login("+919900000003"), "EMAIL", "closed@example.test").andReturn(), 422,
                "business-rule", PATH);
        assertThat(count("SELECT COUNT(*) FROM contact_verifications")).isZero();
    }

    @Test
    void aNewStartSupersedesThePendingOneOfTheSameChannelOnly() throws Exception {
        Cookie session = login("+919900000001");
        long first = startedId(start(session, "EMAIL", "first@example.test").andReturn(), null);
        long number = startedId(start(session, "SECONDARY_MSISDN", "+919811113333").andReturn(), null);
        String firstCode = sender.lastCode("first@example.test").orElseThrow();
        long second = startedId(start(session, "EMAIL", "second@example.test").andReturn(), null);

        assertThat(verification(first).get("status")).isEqualTo("SUPERSEDED");
        assertThat(verification(number).get("status")).isEqualTo("PENDING");
        Map<String, Object> problem = verificationProblem(confirm(session, first, firstCode).andReturn());
        assertThat(problem).doesNotContainKey("attemptsRemaining");
        assertThat(email(C1)).isEqualTo("test.customer1@example.test");

        confirm(session, second, sender.lastCode("second@example.test").orElseThrow()).andExpect(status().isOk());
        assertThat(email(C1)).isEqualTo("second@example.test");
    }

    // ------------------------------------------------------------------ §3.2 attempts, lock, races

    @Test
    void aWrongCodeIsCountedInACommittedWriteThatSurvivesThe422() throws Exception {
        Cookie session = login("+919900000002");
        long id = startedId(start(session, "EMAIL", "wrong@example.test").andReturn(), null);
        String code = sender.lastCode("wrong@example.test").orElseThrow();

        MvcResult result = confirm(session, id, otherCode(code)).andReturn();

        assertThat(verificationProblem(result).get("attemptsRemaining")).isEqualTo(4);
        assertThat(verification(id).get("attempts")).isEqualTo(1);
        assertThat(verification(id).get("status")).isEqualTo("PENDING");
        Map<String, Object> audit = auditByCorrelation("CONTACT_VERIFICATION_FAILED", result);
        assertThat(audit.get("outcome")).isEqualTo("FAILURE");
        assertThat(details(audit)).isEqualTo(Map.of("channel", "EMAIL", "reason", "wrong_code",
                "attemptsRemaining", 4));
        assertThat(email(C2)).isNull();

        // the counted attempt still allows the right code afterwards
        confirm(session, id, code).andExpect(status().isOk());
        assertThat(verification(id).get("attempts")).isEqualTo(2);
    }

    @Test
    void fiveWrongCodesLockTheVerificationAndTheRightCodeIsThenRefused() throws Exception {
        Cookie session = login("+919900000002");
        long id = startedId(start(session, "EMAIL", "lock@example.test").andReturn(), null);
        String code = sender.lastCode("lock@example.test").orElseThrow();

        for (int remaining = 4; remaining >= 0; remaining--) {
            assertThat(verificationProblem(confirm(session, id, otherCode(code)).andReturn()).get("attemptsRemaining"))
                    .isEqualTo(remaining);
        }
        assertThat(verification(id).get("status")).isEqualTo("LOCKED");
        assertThat(verification(id).get("attempts")).isEqualTo(5);

        MvcResult locked = confirm(session, id, code).andReturn();

        Map<String, Object> problem = verificationProblem(locked);
        assertThat(problem.get("attemptsRemaining")).isEqualTo(0);
        assertThat((String) problem.get("detail")).contains("Too many wrong attempts");
        assertThat(details(auditByCorrelation("CONTACT_VERIFICATION_FAILED", locked)).get("reason")).isEqualTo("locked");
        assertThat(verification(id).get("attempts")).isEqualTo(5);
        assertThat(email(C2)).isNull();
    }

    @Test
    void eightParallelWrongConfirmsCountAtMostFiveAttemptsAndNeverSucceed() throws Exception {
        Cookie owner = login("+919900000002");
        long id = startedId(start(owner, "EMAIL", "race-wrong@example.test").andReturn(), null);
        String wrong = otherCode(sender.lastCode("race-wrong@example.test").orElseThrow());

        List<Integer> statuses = parallel(8, "+919900000002", id, i -> wrong);

        assertThat(statuses).hasSize(8).containsOnly(422);
        assertThat(verification(id).get("attempts")).isEqualTo(5);
        assertThat(verification(id).get("status")).isEqualTo("LOCKED");
        assertThat(email(C2)).isNull();
    }

    @Test
    void twoParallelCorrectConfirmsVerifyOnceAndAuditOnce() throws Exception {
        Cookie owner = login("+919900000002");
        long id = startedId(start(owner, "EMAIL", "race-right@example.test").andReturn(), null);
        String code = sender.lastCode("race-right@example.test").orElseThrow();
        long auditsBefore = contactChangedAudits(C2);

        List<Integer> statuses = parallel(2, "+919900000002", id, i -> code);

        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(verification(id).get("status")).isEqualTo("VERIFIED");
        assertThat(email(C2)).isEqualTo("race-right@example.test");
        assertThat(contactChangedAudits(C2)).isEqualTo(auditsBefore + 1);
    }

    @Test
    void parallelWrongAndRightConfirmsNeverExceedTheLimitAndSucceedAtMostOnce() throws Exception {
        Cookie owner = login("+919900000002");
        long id = startedId(start(owner, "EMAIL", "race-mixed@example.test").andReturn(), null);
        String code = sender.lastCode("race-mixed@example.test").orElseThrow();
        String wrong = otherCode(code);

        List<Integer> statuses = parallel(8, "+919900000002", id, i -> i == 3 ? code : wrong);

        assertThat(verification(id).get("attempts")).isEqualTo(5);
        long successes = statuses.stream().filter(s -> s == 200).count();
        assertThat(successes).isLessThanOrEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 422);
        if (successes == 1) {
            assertThat(verification(id).get("status")).isEqualTo("VERIFIED");
            assertThat(email(C2)).isEqualTo("race-mixed@example.test");
        } else {
            assertThat(verification(id).get("status")).isEqualTo("LOCKED");
            assertThat(email(C2)).isNull();
        }
    }

    @Test
    void anExpiredCodeIsRefusedWithoutCountingAnAttempt() throws Exception {
        Cookie session = login("+919900000002");
        long id = startedId(start(session, "EMAIL", "late@example.test").andReturn(), null);
        String code = sender.lastCode("late@example.test").orElseThrow();
        CLOCK.set(T0.plus(Duration.ofMinutes(10)));

        MvcResult result = confirm(session, id, code).andReturn();

        Map<String, Object> problem = verificationProblem(result);
        assertThat(problem).doesNotContainKey("attemptsRemaining");
        assertThat(details(auditByCorrelation("CONTACT_VERIFICATION_FAILED", result)).get("reason"))
                .isEqualTo("expired");
        assertThat(verification(id).get("attempts")).isEqualTo(0);
        assertThat(email(C2)).isNull();
    }

    @Test
    void anotherCustomersOrAnUnknownVerificationIsNotFound() throws Exception {
        long foreign = startedId(start(login("+919900000001"), "EMAIL", "mine@example.test").andReturn(), null);
        String code = sender.lastCode("mine@example.test").orElseThrow();
        Cookie other = login("+919900000002");

        assertProblem(confirm(other, foreign, code).andReturn(), 404, "not-found", confirmPath(foreign));
        assertProblem(confirm(other, foreign + 1000, code).andReturn(), 404, "not-found", confirmPath(foreign + 1000));

        assertThat(verification(foreign).get("attempts")).isEqualTo(0);
        assertThat(verification(foreign).get("status")).isEqualTo("PENDING");
        assertThat(email(C1)).isEqualTo("test.customer1@example.test");
    }

    @Test
    void malformedCodeIsAValidationErrorAndCountsNothing() throws Exception {
        Cookie session = login("+919900000002");
        long id = startedId(start(session, "EMAIL", "fmt@example.test").andReturn(), null);

        for (String bad : List.of("12345", "1234567", "abcdef")) {
            Map<String, Object> problem = assertProblem(confirm(session, id, bad).andReturn(), 400, "validation",
                    confirmPath(id));
            assertThat(fields(problem)).containsExactly("code");
        }
        assertThat(verification(id).get("attempts")).isEqualTo(0);
    }

    // ------------------------------------------------------------------ rate limit, sender down

    @Test
    void theSixthStartWithinAnHourIsRateLimited() throws Exception {
        Cookie session = login("+919900000001");
        for (int i = 0; i < 5; i++) {
            CLOCK.set(T0.plus(Duration.ofMinutes(i)));
            start(session, "EMAIL", "rate" + i + "@example.test").andExpect(status().isCreated());
        }
        CLOCK.set(T0.plus(Duration.ofMinutes(30)));

        MvcResult limited = start(session, "EMAIL", "rate5@example.test").andReturn();

        assertProblem(limited, 429, "too-many-requests", PATH);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(30 * 60));
        assertThat(count("SELECT COUNT(*) FROM contact_verifications")).isEqualTo(5);
        assertThat(sender.lastCode("rate5@example.test")).isEmpty();

        CLOCK.set(T0.plus(Duration.ofMinutes(60)).plusSeconds(1)); // the first start has left the window
        start(session, "EMAIL", "rate5@example.test").andExpect(status().isCreated());
    }

    @Test
    void senderDownIs503AndTheVerificationIsExpired() throws Exception {
        Cookie session = login("+919900000002");
        sender.setFailing(true);

        assertProblem(start(session, "EMAIL", "down@example.test").andReturn(), 503, "dependency-unavailable", PATH);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM contact_verifications", Map.of());
        assertThat(rows).containsExactly(Map.of("status", "EXPIRED"));
        assertThat(email(C2)).isNull();
    }

    @Test
    void theCodeNeverAppearsInLogsAuditRowsOrResponses(CapturedOutput output) throws Exception {
        Cookie session = login("+919900000002");
        int logStart = output.getAll().length();
        MvcResult started = start(session, "EMAIL", "secret@example.test").andReturn();
        long id = startedId(started, null);
        String code = sender.lastCode("secret@example.test").orElseThrow();
        MvcResult wrong = confirm(session, id, otherCode(code)).andReturn();
        MvcResult right = confirm(session, id, code).andReturn();
        sender.setFailing(true);
        MvcResult failed = start(session, "SECONDARY_MSISDN", "+919811114444").andReturn();

        assertThat(output.getAll().substring(logStart)).doesNotContain(code);
        for (MvcResult r : List.of(started, wrong, right, failed)) {
            assertThat(r.getResponse().getContentAsString()).doesNotContain(code);
        }
        List<String> audit = jdbc.queryForList("""
                SELECT COALESCE(details, '') FROM audit_events
                WHERE action LIKE 'CONTACT_VERIFICATION_%' OR action = 'PROFILE_CONTACT_CHANGED'
                """, Map.of(), String.class);
        assertThat(audit).isNotEmpty().noneMatch(d -> d.contains(code));
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        assertProblem(mvc.perform(post(PATH).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content(startBody("EMAIL", "a@example.test")))
                .andReturn(), 401, "unauthenticated", PATH);
        assertProblem(mvc.perform(post(confirmPath(1)).cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andReturn(), 401, "unauthenticated", confirmPath(1));
    }

    @Test
    void staffAndUnlinkedCustomersAreForbidden() throws Exception {
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            Cookie session = login(username);
            assertProblem(start(session, "EMAIL", "a@example.test").andReturn(), 403, "forbidden", PATH);
            assertProblem(confirm(session, 1, "123456").andReturn(), 403, "forbidden", confirmPath(1));
        }
        assertThat(count("SELECT COUNT(*) FROM contact_verifications")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private interface CodeFor {
        String code(int i);
    }

    /** {@code n} confirms released together, each from its own session of {@code username}; returns the statuses. */
    private List<Integer> parallel(int n, String username, long id, CodeFor codes) throws Exception {
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sessions.add(login(username));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                Callable<Integer> call = () -> {
                    go.await();
                    return confirm(sessions.get(k), id, codes.code(k)).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
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

    private org.springframework.test.web.servlet.ResultActions start(Cookie session, String channel, String value)
            throws Exception {
        return mvc.perform(post(PATH)
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(startBody(channel, value)));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Cookie session, long id, String code)
            throws Exception {
        return mvc.perform(post(confirmPath(id))
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))));
    }

    private String startBody(String channel, String value) throws Exception {
        return objectMapper.writeValueAsString(Map.of("channel", channel, "value", value));
    }

    private static String confirmPath(long id) {
        return PATH + "/" + id + "/confirm";
    }

    private long startedId(MvcResult result, String expectedMask) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> body = json(result);
        if (expectedMask != null) {
            assertThat(body.get("maskedDestination")).isEqualTo(expectedMask);
        }
        return ((Number) body.get("id")).longValue();
    }

    /** A 422 business-rule {@code VerificationProblem}: the ProblemDetail keys plus optional attemptsRemaining. */
    private Map<String, Object> verificationProblem(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        Map<String, Object> body = json(result);
        assertThat(body.get("type")).isEqualTo("https://telecom.example/problems/business-rule");
        assertThat(body.keySet()).isSubsetOf("type", "title", "status", "detail", "instance", "correlationId",
                "attemptsRemaining");
        assertThat(body.get("correlationId")).isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
        return body;
    }

    private static String otherCode(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("errors")).stream().map(e -> (String) e.get("field")).toList();
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private Map<String, Object> details(Map<String, Object> audit) throws Exception {
        return objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
    }

    private Map<String, Object> auditByCorrelation(String action, MvcResult result) {
        return jdbc.queryForMap("""
                SELECT actor_username, entity_type, entity_id, outcome, details
                FROM audit_events WHERE action = :action AND correlation_id = :cid
                """, Map.of("action", action, "cid", result.getResponse().getHeader("X-Correlation-Id")));
    }

    private Map<String, Object> verification(long id) {
        return jdbc.queryForMap("SELECT new_value, code_hmac, attempts, status FROM contact_verifications WHERE id = :id",
                Map.of("id", id));
    }

    private long contactChangedAudits(String customerRef) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE action = 'PROFILE_CONTACT_CHANGED' AND entity_id = :id
                """, Map.of("id", String.valueOf(customerId(customerRef))), Long.class);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    private String email(String customerRef) {
        return jdbc.queryForObject("SELECT email FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                String.class);
    }

    private int version(String customerRef) {
        return jdbc.queryForObject("SELECT version FROM customers WHERE customer_ref = :ref",
                Map.of("ref", customerRef), Integer.class);
    }

    private long customerId(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                Long.class);
    }

    /** Logs in like the SPA from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.22." + (n / 250) + "." + (n % 250 + 1);
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
