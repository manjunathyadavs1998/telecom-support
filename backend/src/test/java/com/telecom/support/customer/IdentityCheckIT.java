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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * I2–I4 agent identity checks and the cleanup job end to end (design 03-inbound backend R2–R4, §7, §8, §9; INB-01.1,
 * INB-01.2, §14 #2, NFR-SEC, BR-10). Time moves only through {@link InboundTestClock}; nothing sleeps. DEBUG logging makes
 * the "code and answers never logged" check cover the debug paths. Test data: R__test_users_03_inbound.sql
 * (CUST-T-0006: primary +919900000006, second line +919900000016, KBA 560001 + test.customer6@example.test;
 * CUST-T-0007: KBA 110001 + +919900000017; CUST-T-0008: no verified contact).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class IdentityCheckIT extends AbstractMySqlIT {

    private static final String XSRF = "check-xsrf-token";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final String C6_PRIMARY = "+919900000006";

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockVerificationCodeSender sender;

    @Autowired
    LookupRateLimiter limiter;

    @Autowired
    IdentityCheckCleanupJob cleanupJob;

    private long c6;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        limiter.clear();
        sender.setFailing(false);
        sender.clear();
        cleanTables();
        c6 = customerId("CUST-T-0006");
    }

    @AfterEach
    void cleanUp() {
        sender.setFailing(false);
        jdbc.update("UPDATE subscriptions SET status = 'SUSPENDED' WHERE msisdn = :m", Map.of("m", C6_PRIMARY));
        cleanTables();
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM customer_lookups", Map.of());
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", Map.of("t", utc(T0.minus(Duration.ofDays(2)))));
    }

    // ------------------------------------------------------------------ OTP

    @Test
    void otpStartThenConfirmVerifiesTheAgentForThirtyMinutes() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);

        MvcResult started = otp(agent, c6, token).andExpect(status().isCreated()).andReturn();

        Map<String, Object> body = json(started);
        assertThat(body).containsOnlyKeys("id", "method", "status", "maskedDestination", "expiresAt", "validUntil");
        assertThat(body.get("method")).isEqualTo("OTP");
        assertThat(body.get("status")).isEqualTo("PENDING");
        assertThat(body.get("maskedDestination")).isEqualTo("+91******0006");
        assertThat(Instant.parse((String) body.get("expiresAt"))).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(body.get("validUntil")).isNull();
        long id = id(body);
        String code = sender.lastCode(C6_PRIMARY).orElseThrow();
        Map<String, Object> row = check(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("agent_user_id")).longValue()).isEqualTo(userId("T-IN"));
        assertThat((String) row.get("code_hmac")).matches("[0-9a-f]{64}").doesNotContain(code);
        Map<String, Object> startAudit = audit("IDENTITY_CHECK_STARTED", started);
        assertThat(startAudit.get("entity_type")).isEqualTo("CUSTOMER");
        assertThat(startAudit.get("entity_id")).isEqualTo("CUST-T-0006");
        Map<String, Object> expected = new HashMap<>();
        expected.put("method", "OTP");
        expected.put("checkId", (int) id);
        expected.put("validUntil", null);
        assertThat(details(startAudit)).isEqualTo(expected);

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(2)));
        MvcResult confirmed = confirm(agent, c6, id, code).andExpect(status().isOk()).andReturn();

        Map<String, Object> verified = json(confirmed);
        Instant validUntil = T0.plus(Duration.ofMinutes(32));
        assertThat(verified.get("status")).isEqualTo("VERIFIED");
        assertThat(Instant.parse((String) verified.get("validUntil"))).isEqualTo(validUntil);
        assertThat(check(id).get("status")).isEqualTo("VERIFIED");
        assertThat(details(audit("IDENTITY_CHECK_PASSED", confirmed)))
                .isEqualTo(Map.of("method", "OTP", "checkId", (int) id, "validUntil", validUntil.toString()));

        Map<String, Object> current = json(current(agent, c6).andExpect(status().isOk()).andReturn());
        assertThat(id(current)).isEqualTo(id);
        assertThat(Instant.parse((String) current.get("validUntil"))).isEqualTo(validUntil);
        assertThat(current(login("T-IN-REACT"), c6).andReturn().getResponse().getStatus()).isEqualTo(404);

        InboundTestClock.CLOCK.set(validUntil); // valid_until is exclusive and never extended
        assertProblem(current(agent, c6).andReturn(), 404, "not-found", path(c6) + "/current");
    }

    @Test
    void aWrongCodeIsCountedAndAudited() throws Exception {
        Cookie agent = login("T-IN");
        long id = id(json(otp(agent, c6, lookupToken(agent, C6_PRIMARY)).andReturn()));
        String code = sender.lastCode(C6_PRIMARY).orElseThrow();

        MvcResult wrong = confirm(agent, c6, id, otherCode(code)).andReturn();

        Map<String, Object> problem = verificationProblem(wrong, "wrong_code");
        assertThat(problem.get("attemptsRemaining")).isEqualTo(4);
        assertThat(check(id).get("attempts")).isEqualTo(1);
        Map<String, Object> failed = audit("IDENTITY_CHECK_FAILED", wrong);
        assertThat(failed.get("outcome")).isEqualTo("FAILURE");
        assertThat(details(failed)).isEqualTo(Map.of("method", "OTP", "checkId", (int) id, "reason", "wrong_code",
                "attemptsRemaining", 4));

        confirm(agent, c6, id, code).andExpect(status().isOk());
        assertThat(check(id).get("attempts")).isEqualTo(2);
    }

    @Test
    void eightParallelWrongConfirmsCountExactlyFiveAttemptsAndLock() throws Exception {
        Cookie agent = login("T-IN");
        long id = id(json(otp(agent, c6, lookupToken(agent, C6_PRIMARY)).andReturn()));
        String code = sender.lastCode(C6_PRIMARY).orElseThrow();
        String wrong = otherCode(code);

        List<Integer> statuses = parallel(8, "T-IN", (session, i) -> confirm(session, c6, id, wrong));

        assertThat(statuses).hasSize(8).containsOnly(422);
        assertThat(check(id).get("attempts")).isEqualTo(5);
        assertThat(check(id).get("status")).isEqualTo("LOCKED");
        Map<String, Object> locked = verificationProblem(confirm(agent, c6, id, code).andReturn(), "locked");
        assertThat(locked.get("attemptsRemaining")).isEqualTo(0);
        assertThat(current(agent, c6).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anExpiredOrSupersededCodeIsRefusedWithoutCountingAnAttempt() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);
        long first = id(json(otp(agent, c6, token).andReturn()));
        String firstCode = sender.lastCode(C6_PRIMARY).orElseThrow();
        long second = id(json(otp(agent, c6, token).andReturn()));

        assertThat(check(first).get("status")).isEqualTo("SUPERSEDED");
        assertThat(verificationProblem(confirm(agent, c6, first, firstCode).andReturn(), "expired"))
                .doesNotContainKey("attemptsRemaining");
        assertThat(check(first).get("attempts")).isEqualTo(0);

        String secondCode = sender.lastCode(C6_PRIMARY).orElseThrow();
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(10)));
        verificationProblem(confirm(agent, c6, second, secondCode).andReturn(), "expired");
        assertThat(check(second).get("attempts")).isEqualTo(0);
    }

    @Test
    void tenParallelOtpStartsCreateExactlyFiveChecks() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);
        long rateLimitedBefore = rateLimitedAudits();

        List<Integer> statuses = parallel(10, "T-IN", (session, i) -> otp(session, c6, token));

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(5);
        assertThat(statuses.stream().filter(s -> s == 429)).hasSize(5);
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c AND method = 'OTP'")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c AND status = 'PENDING'"))
                .isEqualTo(1);
        assertThat(rateLimitedAudits()).isEqualTo(rateLimitedBefore + 5);

        // the limit is per customer for every agent; Retry-After counts from the oldest start
        Cookie other = login("T-IN-REACT");
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(20)));
        MvcResult limited = otp(other, c6, lookupToken(other, C6_PRIMARY)).andReturn();
        assertProblem(limited, 429, "too-many-requests", path(c6));
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(40 * 60));
    }

    @Test
    void senderDownIs503AndTheCheckIsExpired() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);
        sender.setFailing(true);

        assertProblem(otp(agent, c6, token).andReturn(), 503, "dependency-unavailable", path(c6));

        assertThat(jdbc.queryForList("SELECT status FROM identity_checks WHERE customer_id = :c",
                Map.of("c", c6), String.class)).containsExactly("EXPIRED");
    }

    @Test
    void noLivePrimaryNumberIsNoOtpDestination() throws Exception {
        jdbc.update("UPDATE subscriptions SET status = 'TERMINATED' WHERE msisdn = :m", Map.of("m", C6_PRIMARY));
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, "+919900000016");

        Map<String, Object> problem = verificationProblem(otp(agent, c6, token).andReturn(), "no_otp_destination");

        assertThat(problem).doesNotContainKey("attemptsRemaining");
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c")).isZero();
        assertThat(sender.lastCode(C6_PRIMARY)).isEmpty();
    }

    // ------------------------------------------------------------------ lookup binding (R2)

    @Test
    void unknownIdAndMissingExpiredOrForeignTokensGiveTheSame404() throws Exception {
        Cookie agent = login("T-IN");
        String mine = lookupToken(agent, C6_PRIMARY);
        String othersToken = lookupToken(login("T-IN-REACT"), C6_PRIMARY);
        long c7 = customerId("CUST-T-0007");
        long unknown = 9_999_999L;

        List<MvcResult> results = new ArrayList<>();
        results.add(otp(agent, unknown, mine).andReturn());                        // unknown id
        results.add(otp(agent, c6, "never-issued-token-0123456789").andReturn());  // no search hit
        results.add(otp(agent, c6, othersToken).andReturn());                     // another agent's token
        results.add(kba(agent, c7, mine, "110001", "+919900000017").andReturn());  // another customer's token
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(15)));
        results.add(otp(agent, c6, mine).andReturn());                            // expired token

        List<Map<String, Object>> shapes = new ArrayList<>();
        for (MvcResult r : results) {
            Map<String, Object> problem = assertProblem(r, 404, "not-found", r.getRequest().getRequestURI());
            problem.remove("instance");
            problem.remove("correlationId");
            shapes.add(problem);
        }
        assertThat(shapes).allMatch(s -> s.equals(shapes.get(0)));
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id IN (:c, " + c7 + ")")).isZero();
        assertThat(sender.lastCode(C6_PRIMARY)).isEmpty();

        Map<String, Object> missing = assertProblem(mvc.perform(post(path(c6))
                        .cookie(agent, new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"OTP\"}")).andReturn(),
                400, "validation", path(c6));
        assertThat(fields(missing)).containsExactly("lookupToken");
    }

    // ------------------------------------------------------------------ KBA (R4)

    @Test
    void rightKbaAnswersVerifyAtOnceAndSupersedeAPendingOtp() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);
        long otpId = id(json(otp(agent, c6, token).andReturn()));

        MvcResult result = kba(agent, c6, token, " 560 001 ", " Test.Customer6@Example.TEST ").andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> body = json(result);
        assertThat(body.get("method")).isEqualTo("KBA");
        assertThat(body.get("status")).isEqualTo("VERIFIED");
        assertThat(body.get("maskedDestination")).isNull();
        assertThat(Instant.parse((String) body.get("validUntil"))).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        long id = id(body);
        assertThat(check(id).get("status")).isEqualTo("VERIFIED");
        assertThat(check(id).get("code_hmac")).isNull();
        assertThat(check(otpId).get("status")).isEqualTo("SUPERSEDED");
        assertThat(details(audit("IDENTITY_CHECK_PASSED", result)).get("method")).isEqualTo("KBA");

        // a verified secondary number is an accepted registered contact too
        long c7 = customerId("CUST-T-0007");
        MvcResult secondary = kba(agent, c7, lookupToken(agent, "+919900000007"), "110001", "+91 99000-00017")
                .andReturn();
        assertThat(secondary.getResponse().getStatus()).isEqualTo(201);
        assertThat(id(json(current(agent, c7).andReturn()))).isEqualTo(id(json(secondary)));
    }

    @Test
    void wrongKbaAnswersFailAndAreAuditedWithoutTheAnswers() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);

        MvcResult wrongPostal = kba(agent, c6, token, "560002", "test.customer6@example.test").andReturn();
        MvcResult wrongContact = kba(agent, c6, token, "560001", "+919900000016").andReturn(); // not a verified contact

        assertThat(verificationProblem(wrongPostal, "kba_mismatch").get("attemptsRemaining")).isEqualTo(2);
        assertThat(verificationProblem(wrongContact, "kba_mismatch").get("attemptsRemaining")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM identity_checks WHERE customer_id = :c",
                Map.of("c", c6), String.class)).containsExactly("FAILED", "FAILED");
        Map<String, Object> audit = audit("IDENTITY_CHECK_FAILED", wrongPostal);
        assertThat(details(audit)).containsEntry("method", "KBA").containsEntry("reason", "kba_mismatch")
                .containsEntry("attemptsRemaining", 2);
        assertThat((String) audit.get("details")).doesNotContain("560002", "test.customer6");
        assertThat(current(agent, c6).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void tenParallelWrongKbaAttemptsEvaluateExactlyThree() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);

        List<Integer> statuses = parallel(10, "T-IN",
                (session, i) -> kba(session, c6, token, "999999", "nobody@example.test"));

        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 429)).hasSize(7);
        assertThat(jdbc.queryForList("SELECT status FROM identity_checks WHERE customer_id = :c",
                Map.of("c", c6), String.class)).containsExactly("FAILED", "FAILED", "FAILED");

        // the right answers are refused too while the window is full
        assertProblem(kba(agent, c6, token, "560001", "test.customer6@example.test").andReturn(), 429,
                "too-many-requests", path(c6));
    }

    @Test
    void aCrashLeftCheckingRowCountsAsAFailedAttempt() throws Exception {
        long other = userId("T-IN-REACT");
        for (int i = 1; i <= 2; i++) {
            insertKbaRow(other, c6, "CHECKING", T0.minus(Duration.ofMinutes(10 * i)));
        }
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);

        assertThat(verificationProblem(kba(agent, c6, token, "111111", "x@example.test").andReturn(), "kba_mismatch")
                .get("attemptsRemaining")).isEqualTo(0);
        MvcResult limited = kba(agent, c6, token, "560001", "test.customer6@example.test").andReturn();

        assertProblem(limited, 429, "too-many-requests", path(c6));
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(40 * 60));
    }

    @Test
    void theAgentKbaLimitSpansCustomers() throws Exception {
        long agentId = userId("T-IN");
        long c1 = customerId("CUST-T-0001");
        for (int i = 0; i < 10; i++) {
            insertKbaRow(agentId, c1, "FAILED", T0.minus(Duration.ofMinutes(50 - i)));
        }
        Cookie agent = login("T-IN");

        assertProblem(kba(agent, c6, lookupToken(agent, C6_PRIMARY), "560001", "test.customer6@example.test")
                .andReturn(), 429, "too-many-requests", path(c6));
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c")).isZero();

        // another agent is not limited by T-IN's attempts
        Cookie other = login("T-IN-REACT");
        kba(other, c6, lookupToken(other, C6_PRIMARY), "560001", "test.customer6@example.test")
                .andExpect(status().isCreated());
    }

    @Test
    void kbaWithoutAVerifiedContactIsUnavailable() throws Exception {
        Cookie agent = login("T-IN");
        long c8 = customerId("CUST-T-0008");

        Map<String, Object> problem = verificationProblem(kba(agent, c8, lookupToken(agent, "+919900000008"), "500001",
                "someone@example.test").andReturn(), "kba_unavailable");

        assertThat(problem).doesNotContainKey("attemptsRemaining");
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = " + c8)).isZero();
    }

    @Test
    void kbaNeedsBothAnswers() throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);

        Map<String, Object> problem = assertProblem(mvc.perform(post(path(c6))
                        .cookie(agent, new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("method", "KBA", "lookupToken", token))))
                .andReturn(), 400, "validation", path(c6));

        assertThat(fields(problem)).containsExactlyInAnyOrder("postalCode", "registeredContact");
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c")).isZero();
    }

    // ------------------------------------------------------------------ scope of I3 / I4

    @Test
    void anotherAgentsOrCustomersCheckIsNotFoundAndNothingIsCounted() throws Exception {
        Cookie agent = login("T-IN");
        long id = id(json(otp(agent, c6, lookupToken(agent, C6_PRIMARY)).andReturn()));
        String code = sender.lastCode(C6_PRIMARY).orElseThrow();
        long c7 = customerId("CUST-T-0007");

        assertProblem(confirm(login("T-IN-REACT"), c6, id, code).andReturn(), 404, "not-found", confirmPath(c6, id));
        assertProblem(confirm(agent, c7, id, code).andReturn(), 404, "not-found", confirmPath(c7, id));
        assertProblem(confirm(agent, c6, id + 1000, code).andReturn(), 404, "not-found", confirmPath(c6, id + 1000));

        assertThat(check(id).get("attempts")).isEqualTo(0);
        assertThat(check(id).get("status")).isEqualTo("PENDING");
        assertProblem(current(agent, c6).andReturn(), 404, "not-found", path(c6) + "/current");
    }

    // ------------------------------------------------------------------ cleanup job

    @Test
    void cleanupExpiresPendingChecksAndPurgesOldLookups() throws Exception {
        Cookie agent = login("T-IN");
        long id = id(json(otp(agent, c6, lookupToken(agent, C6_PRIMARY)).andReturn()));
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofHours(23)));
        lookupToken(login("T-IN"), C6_PRIMARY); // a fresh lookup that must survive

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofHours(24)).plusSeconds(1));
        IdentityCheckCleanupJob.Result result = cleanupJob.run();

        assertThat(result.expiredChecks()).isGreaterThanOrEqualTo(1);
        assertThat(check(id).get("status")).isEqualTo("EXPIRED");
        assertThat(result.purgedLookups()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM customer_lookups")).isEqualTo(1);
        assertThat(cleanupJob.run()).isEqualTo(new IdentityCheckCleanupJob.Result(0, 0));
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void theCodeAndTheAnswersNeverAppearInLogsAuditOrResponses(CapturedOutput output) throws Exception {
        Cookie agent = login("T-IN");
        String token = lookupToken(agent, C6_PRIMARY);
        int logStart = output.getAll().length();
        List<MvcResult> results = new ArrayList<>();
        MvcResult started = otp(agent, c6, token).andReturn();
        results.add(started);
        long id = id(json(started));
        String code = sender.lastCode(C6_PRIMARY).orElseThrow();
        results.add(confirm(agent, c6, id, otherCode(code)).andReturn());
        results.add(confirm(agent, c6, id, code).andReturn());
        results.add(kba(agent, c6, token, "771234", "kba.secret.answer@example.test").andReturn());
        results.add(kba(agent, c6, token, "560001", "test.customer6@example.test").andReturn());
        sender.setFailing(true);
        results.add(otp(agent, c6, token).andReturn());

        String logs = output.getAll().substring(logStart);
        assertThat(logs).doesNotContain(code, "771234", "kba.secret.answer", "test.customer6@example.test", token);
        for (MvcResult r : results) {
            assertThat(r.getResponse().getContentAsString())
                    .doesNotContain(code, "771234", "kba.secret.answer", "560001", "test.customer6", token);
        }
        List<String> audit = jdbc.queryForList("""
                SELECT COALESCE(details, '') FROM audit_events
                WHERE action LIKE 'IDENTITY_CHECK_%' AND occurred_at >= :t
                """, Map.of("t", utc(T0.minus(Duration.ofDays(1)))), String.class);
        assertThat(audit).isNotEmpty().noneMatch(d -> d.contains(code) || d.contains("771234")
                || d.contains("kba.secret") || d.contains("560001") || d.contains("test.customer6"));
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        Cookie xsrf = new Cookie("XSRF-TOKEN", XSRF);
        assertProblem(mvc.perform(post(path(c6)).cookie(xsrf).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content(otpBody("x".repeat(22)))).andReturn(),
                401, "unauthenticated", path(c6));
        assertProblem(mvc.perform(post(confirmPath(c6, 1)).cookie(xsrf).header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")).andReturn(),
                401, "unauthenticated", confirmPath(c6, 1));
        assertProblem(mvc.perform(get(path(c6) + "/current")).andReturn(), 401, "unauthenticated",
                path(c6) + "/current");
    }

    @Test
    void customersAndNonInboundStaffAreForbidden() throws Exception {
        for (String username : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(username);
            assertProblem(otp(session, c6, "x".repeat(22)).andReturn(), 403, "forbidden", path(c6));
            assertProblem(confirm(session, c6, 1, "123456").andReturn(), 403, "forbidden", confirmPath(c6, 1));
            assertProblem(current(session, c6).andReturn(), 403, "forbidden", path(c6) + "/current");
        }
        assertThat(count("SELECT COUNT(*) FROM identity_checks WHERE customer_id = :c")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private interface Call {
        ResultActions perform(Cookie session, int i) throws Exception;
    }

    /** {@code n} calls released together, each from its own session of {@code username}; returns the statuses. */
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
                int k = i;
                Callable<Integer> task = () -> {
                    go.await();
                    return call.perform(sessions.get(k), k).andReturn().getResponse().getStatus();
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

    private String lookupToken(Cookie session, String msisdn) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/agent/customer-searches")
                        .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("msisdn", msisdn))))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> matches = objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<>() {});
        return (String) matches.get(0).get("lookupToken");
    }

    private ResultActions otp(Cookie session, long customerId, String token) throws Exception {
        return postJson(session, path(customerId), otpBody(token));
    }

    private ResultActions kba(Cookie session, long customerId, String token, String postalCode, String contact)
            throws Exception {
        return postJson(session, path(customerId), objectMapper.writeValueAsString(Map.of("method", "KBA",
                "lookupToken", token, "postalCode", postalCode, "registeredContact", contact)));
    }

    private ResultActions confirm(Cookie session, long customerId, long id, String code) throws Exception {
        return postJson(session, confirmPath(customerId, id), objectMapper.writeValueAsString(Map.of("code", code)));
    }

    private ResultActions current(Cookie session, long customerId) throws Exception {
        return mvc.perform(get(path(customerId) + "/current").cookie(session));
    }

    private ResultActions postJson(Cookie session, String path, String body) throws Exception {
        return mvc.perform(post(path)
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String otpBody(String token) throws Exception {
        return objectMapper.writeValueAsString(Map.of("method", "OTP", "lookupToken", token));
    }

    private static String path(long customerId) {
        return "/api/v1/agent/customers/" + customerId + "/identity-checks";
    }

    private static String confirmPath(long customerId, long id) {
        return path(customerId) + "/" + id + "/confirm";
    }

    /** A 422 business-rule {@code VerificationProblem} with {@code reason}. */
    private Map<String, Object> verificationProblem(MvcResult result, String reason) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        Map<String, Object> body = json(result);
        assertThat(body.get("type")).isEqualTo("https://telecom.example/problems/business-rule");
        assertThat(body.keySet()).isSubsetOf("type", "title", "status", "detail", "instance", "correlationId",
                "reason", "attemptsRemaining");
        assertThat(body.get("reason")).isEqualTo(reason);
        assertThat(body.get("correlationId")).isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
        return body;
    }

    private void insertKbaRow(long agentUserId, long customerId, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', :status, 1, :t, :t)
                """, Map.of("agent", agentUserId, "customer", customerId, "status", status, "t", utc(createdAt)));
    }

    private static String otherCode(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
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

    private Map<String, Object> audit(String action, MvcResult result) {
        return jdbc.queryForMap("""
                SELECT actor_username, entity_type, entity_id, outcome, details
                FROM audit_events WHERE action = :action AND correlation_id = :cid
                """, Map.of("action", action, "cid", result.getResponse().getHeader("X-Correlation-Id")));
    }

    private Map<String, Object> check(long id) {
        return jdbc.queryForMap("""
                SELECT agent_user_id, customer_id, method, status, code_hmac, attempts FROM identity_checks WHERE id = :id
                """, Map.of("id", id));
    }

    /** {@code :c} is CUST-T-0006, {@code :t} the start of the test window. */
    private long count(String sql) {
        return jdbc.queryForObject(sql, Map.of("c", c6, "t", utc(T0.minus(Duration.ofDays(1)))), Long.class);
    }

    private long rateLimitedAudits() {
        return count("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'IDENTITY_CHECK_FAILED' AND entity_id = 'CUST-T-0006' AND occurred_at >= :t
                  AND JSON_UNQUOTE(JSON_EXTRACT(details, '$.reason')) = 'rate_limited'
                """);
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
        String ip = "10.32." + (n / 250) + "." + (n % 250 + 1);
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
