package com.telecom.support.reactivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.customer.InboundTestClock;
import com.telecom.support.customer.VerifiedProbeController;
import com.telecom.support.reactivation.MockProvisioningClient.Mode;
import com.telecom.support.reactivation.MockProvisioningClient.Operation;
import com.telecom.support.reactivation.ProvisioningClient.State;
import com.telecom.support.support.AbstractMySqlIT;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Reconcile and expiry jobs (design 03-inbound backend §3.1, R15, R16, §7, §9; INB-04.5, BR-03, BR-08, XC-07). Rows are
 * inserted directly for accounts {@code ACC-T-Jnnn} (SUSPENDED NON_PAYMENT, one suspended primary, no email) created
 * and removed by the test; the jobs run by direct calls (crons are {@code -}) at the shared inbound clock, with the
 * in-memory provisioning mock. Audit rows stay and are matched by this test's references.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class ReactivationJobsIT extends AbstractMySqlIT {

    private static final Instant T0 = InboundTestClock.T0;
    private static final Instant TEST_ROWS_FROM = T0.minus(Duration.ofDays(200));
    private static final AtomicInteger REFS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MockProvisioningClient provisioning;

    @Autowired
    ReactivationReconcileJob reconcile;

    @Autowired
    ReactivationExpiryJob expiry;

    @BeforeEach
    void reset() {
        at(T0);
        provisioning.reset();
        cleanTables();
    }

    @AfterEach
    void cleanUp() {
        reconcile.runBudget = ReactivationReconcileJob.RUN_BUDGET;
        expiry.runBudget = ReactivationExpiryJob.RUN_BUDGET;
        cleanTables();
        provisioning.reset();
        at(T0);
    }

    private void cleanTables() {
        Map<String, Object> from = Map.of("t", utc(TEST_ROWS_FROM));
        jdbc.update("DELETE FROM notifications WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM reactivation_requests WHERE created_at >= :t", from);
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", from);
        jdbc.update("""
                DELETE s FROM subscriptions s JOIN accounts a ON a.id = s.account_id
                WHERE a.account_ref LIKE 'ACC-T-J%'
                """, Map.of());
        jdbc.update("DELETE FROM accounts WHERE account_ref LIKE 'ACC-T-J%'", Map.of());
        jdbc.update("DELETE FROM customers WHERE customer_ref LIKE 'CUST-T-J%'", Map.of());
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void aDueRequestOfASuspendedAccountIsSuspendedAndExpiredAndFreesTheAccount() {
        String account = newAccount(1);
        String ref = active(account, 4, T0.minus(Duration.ofHours(4)));
        at(T0.plusSeconds(30));

        ReactivationExpiryJob.RunResult result = expiry.run();

        assertThat(result).isEqualTo(new ReactivationExpiryJob.RunResult(1, 1, 0, 0));
        Map<String, Object> row = row(ref);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("end_reason", "expired")
                .containsEntry("ended_at", utc(T0.plusSeconds(30))).containsEntry("needs_attention", false);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("open_account_id")).isNull();
        assertThat(operations(ref)).containsExactly(Operation.GET_STATUS, Operation.SUSPEND);
        assertThat(provisioning.calls().get(provisioning.calls().size() - 1).msisdns()).containsExactly(msisdn(1));
        assertThat(provisioning.statusOf(ref).orElseThrow().state()).isEqualTo(State.SUSPENDED);
        assertThat(notifications(ref)).containsExactly("REACTIVATION_ENDED/SMS");
        Map<String, Object> expired = details("REACTIVATION_EXPIRED", ref);
        assertThat(expired).containsEntry("from", "EXPIRING").containsEntry("to", "EXPIRED")
                .containsEntry("source", "expiry").containsEntry("endReason", "expired");
        assertThat(auditActor("REACTIVATION_EXPIRED", ref)).isNull();
        // billing owns accounts (R15)
        assertThat(accountStatus(account)).isEqualTo("SUSPENDED");
    }

    @Test
    void aDueRequestOfAReinstatedAccountEndsWithoutAProviderCallAndANotDueOneStays() {
        String reinstated = newAccount(2);
        String due = active(reinstated, 2, T0.minus(Duration.ofHours(2)));
        reinstateAccount(reinstated);
        String later = active(newAccount(3), 2, T0.minus(Duration.ofHours(1)));

        expiry.run();

        assertThat(row(due)).containsEntry("status", "ENDED").containsEntry("end_reason", "account_reinstated")
                .containsEntry("ended_at", utc(T0));
        assertThat(provisioning.callsFor(due)).isEmpty();
        assertThat(notifications(due)).containsExactly("REACTIVATION_ENDED/SMS");
        assertThat(details("REACTIVATION_ENDED", due)).containsEntry("from", "ACTIVE").containsEntry("to", "ENDED")
                .containsEntry("endReason", "account_reinstated");
        assertThat(row(later)).containsEntry("status", "ACTIVE");
        assertThat(provisioning.callsFor(later)).isEmpty();
    }

    @Test
    void aReasonChangedToFraudEndsTheReactivationEarly() {
        String account = newAccount(4);
        String ref = active(account, 8, T0.minus(Duration.ofHours(1)));
        suspendAccount(account, "FRAUD");

        expiry.run();

        assertThat(row(ref)).containsEntry("status", "EXPIRED").containsEntry("end_reason", "policy_prohibited");
        assertThat(operations(ref)).containsExactly(Operation.GET_STATUS, Operation.SUSPEND);
        assertThat(details("REACTIVATION_EXPIRED", ref)).containsEntry("endReason", "policy_prohibited");
    }

    @Test
    void aSuspendFailingTenTimesKeepsRetryingWithBackoffFlagsAttentionOnceAndThenExpires() {
        String ref = active(newAccount(5), 1, T0.minus(Duration.ofHours(1)));
        provisioning.setMode(Mode.SUSPEND_FAIL);
        List<Duration> waits = new ArrayList<>();
        Instant now = T0;
        for (int i = 1; i <= 10; i++) {
            at(now);
            assertThat(expiry.run().retried()).isEqualTo(1);
            Map<String, Object> row = row(ref);
            assertThat(row).containsEntry("status", "EXPIRING").containsEntry("attempts", i)
                    .containsEntry("needs_attention", i >= 5);
            Instant next = instant(row.get("next_attempt_at"));
            waits.add(Duration.between(now, next));
            assertThat(expiry.run().retried()).as("not due before its backoff").isZero();
            now = next;
        }
        assertThat(waits).containsExactly(minutes(1), minutes(2), minutes(5), minutes(15), minutes(60), minutes(60),
                minutes(60), minutes(60), minutes(60), minutes(60));
        assertThat(audits("REACTIVATION_ATTENTION", ref)).isEqualTo(1);
        assertThat(details("REACTIVATION_ATTENTION", ref)).containsEntry("reason", "suspend_failing")
                .containsEntry("attempts", 5);

        provisioning.setMode(Mode.NORMAL);
        at(now);
        expiry.run();

        assertThat(row(ref)).containsEntry("status", "EXPIRED").containsEntry("needs_attention", false);
        assertThat(provisioning.callsFor(ref).stream().filter(c -> c.operation() == Operation.SUSPEND)).hasSize(11);
        assertThat(notifications(ref)).containsExactly("REACTIVATION_ENDED/SMS");
    }

    @Test
    void aProviderReportingSuspendedExpiresWithoutAnotherSuspend() {
        String ref = insert(newAccount(6), "EXPIRING", 2, T0.minus(Duration.ofHours(3)),
                T0.minus(Duration.ofHours(3)), T0.minus(Duration.ofHours(1)), T0, 3);
        provisioning.putStatus(ref, State.SUSPENDED, null);

        expiry.run();

        assertThat(row(ref)).containsEntry("status", "EXPIRED");
        assertThat(operations(ref)).containsExactly(Operation.GET_STATUS);
    }

    @Test
    void aRequestRestoredFor24HoursIsAlertedOverdueOnce() {
        String ref = insert(newAccount(7), "EXPIRING", 4, T0.minus(Duration.ofHours(25)),
                T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(21)), T0.plus(Duration.ofMinutes(30)), 30);

        assertThat(expiry.run().overdue()).isEqualTo(1);
        at(T0.plusSeconds(60));
        assertThat(expiry.run().overdue()).isZero();

        assertThat(audits("REACTIVATION_OVERDUE", ref)).isEqualTo(1);
        assertThat(details("REACTIVATION_OVERDUE", ref)).containsEntry("restoredHours", 25)
                .containsEntry("attempts", 30);
        assertThat(row(ref)).containsEntry("status", "EXPIRING").containsEntry("overdue_alerted_at", utc(T0));
        assertThat(provisioning.callsFor(ref)).as("suspend not yet due").isEmpty();
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    void aRestoreConfirmedWithinItsWindowIsActiveFromTheProvidersRestoreTime() {
        String ref = pending(newAccount(8), 2, T0);
        Instant restoredAt = T0.plusSeconds(10);
        provisioning.putStatus(ref, State.RESTORED, restoredAt);
        at(T0.plus(minutes(1)));

        assertThat(reconcile.run().applied()).isEqualTo(1);

        Map<String, Object> row = row(ref);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("provider_ref", "PRV-" + ref)
                .containsEntry("activated_at", utc(restoredAt))
                .containsEntry("expires_at", utc(restoredAt.plus(Duration.ofHours(2))))
                .containsEntry("failure_reason", null);
        assertThat(notifications(ref)).containsExactly("REACTIVATION_STARTED/SMS");
        assertThat(details("REACTIVATION_ACTIVATED", ref)).containsEntry("from", "PENDING")
                .containsEntry("to", "ACTIVE").containsEntry("source", "reconcile");
        assertThat(auditActor("REACTIVATION_ACTIVATED", ref)).isNull();
    }

    @Test
    void anUnknownRestoreIsReconciledWithBackoffFlaggedAt30MinutesAndALateRestoreGoesStraightToSuspension() {
        String ref = pending(newAccount(9), 1, T0);
        provisioning.setMode(Mode.UNAVAILABLE);
        List<Duration> waits = new ArrayList<>();
        Instant now = T0.plus(minutes(1));
        for (int i = 1; i <= 4; i++) {
            at(now);
            assertThat(reconcile.run().retried()).isEqualTo(1);
            Instant next = instant(row(ref).get("next_attempt_at"));
            waits.add(Duration.between(now, next));
            now = next;
        }
        assertThat(waits).containsExactly(minutes(2), minutes(5), minutes(15), minutes(60));
        assertThat(row(ref)).containsEntry("status", "PENDING").containsEntry("attempts", 4)
                .containsEntry("needs_attention", false);

        // unresolved for 40 min: flagged once (by the first run past 30 min, due or not), still reconciled
        at(T0.plus(minutes(40)));
        assertThat(reconcile.run().flagged()).isEqualTo(1);
        at(T0.plus(minutes(41)));
        assertThat(reconcile.run().flagged()).isZero();
        assertThat(row(ref)).containsEntry("status", "PENDING").containsEntry("needs_attention", true);
        assertThat(audits("REACTIVATION_ATTENTION", ref)).isEqualTo(1);
        assertThat(details("REACTIVATION_ATTENTION", ref)).containsEntry("reason", "unresolved_restore");

        // the provider did restore, 1 s after creation: the 1 h window is over by the next attempt
        provisioning.setMode(Mode.NORMAL);
        provisioning.putStatus(ref, State.RESTORED, T0.plusSeconds(1));
        at(now);
        assertThat(reconcile.run().applied()).isEqualTo(1);
        assertThat(row(ref)).containsEntry("status", "EXPIRING").containsEntry("end_reason", "expired")
                .containsEntry("activated_at", utc(T0.plusSeconds(1)))
                .containsEntry("expires_at", utc(T0.plusSeconds(1).plus(Duration.ofHours(1))))
                .containsEntry("needs_attention", false).containsEntry("attempts", 0);
        assertThat(details("REACTIVATION_ACTIVATED", ref)).containsEntry("to", "EXPIRING");

        expiry.run();

        assertThat(row(ref)).containsEntry("status", "EXPIRED");
        assertThat(provisioning.statusOf(ref).orElseThrow().state()).isEqualTo(State.SUSPENDED);
        assertThat(notifications(ref)).containsExactly("REACTIVATION_ENDED/SMS");
    }

    @Test
    void suspendedOrLongNotFoundFailsAndAYoungNotFoundIsRetried() {
        String suspended = pending(newAccount(10), 2, T0);
        provisioning.putStatus(suspended, State.SUSPENDED, null);
        String old = pending(newAccount(11), 2, T0.minus(minutes(2)));
        String young = pending(newAccount(12), 2, T0);
        at(T0.plus(minutes(1)));

        reconcile.run();

        assertThat(row(suspended)).containsEntry("status", "FAILED").containsEntry("failure_reason", "not_restored");
        assertThat(details("REACTIVATION_FAILED", suspended)).containsEntry("reason", "not_restored")
                .containsEntry("source", "reconcile");
        assertThat(row(old)).containsEntry("status", "FAILED").containsEntry("failure_reason", "not_created");
        assertThat(row(young)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("next_attempt_at", utc(T0.plus(minutes(3))));
        assertThat(notifications(suspended)).isEmpty();
    }

    // ------------------------------------------------------------------ parallel runs, budget

    @Test
    void parallelRunsMakeEachTransitionOnce() throws Exception {
        List<String> due = List.of(active(newAccount(13), 1, T0.minus(Duration.ofHours(1))),
                active(newAccount(14), 1, T0.minus(Duration.ofHours(1))));
        String restored = pending(newAccount(15), 3, T0);
        provisioning.putStatus(restored, State.RESTORED, T0);
        at(T0.plus(minutes(1)));

        parallel(6, i -> i % 2 == 0 ? expiry.run() : reconcile.run());

        for (String ref : due) {
            assertThat(row(ref)).containsEntry("status", "EXPIRED");
            assertThat(audits("REACTIVATION_EXPIRED", ref)).isEqualTo(1);
        }
        assertThat(row(restored)).containsEntry("status", "ACTIVE");
        assertThat(audits("REACTIVATION_ACTIVATED", restored)).isEqualTo(1);
    }

    @Test
    void parallelFailedSuspendsCountOneAttempt() throws Exception {
        String ref = insert(newAccount(16), "EXPIRING", 1, T0.minus(Duration.ofHours(1)),
                T0.minus(Duration.ofHours(1)), T0, T0, 0);
        provisioning.setMode(Mode.SUSPEND_FAIL);

        parallel(4, i -> expiry.run());

        assertThat(row(ref)).containsEntry("status", "EXPIRING").containsEntry("attempts", 1)
                .containsEntry("next_attempt_at", utc(T0.plus(minutes(1))));
    }

    @Test
    void aRunStopsAfterItsBudgetAndTheNextRunContinues() {
        List<String> refs = List.of(pending(newAccount(17), 2, T0), pending(newAccount(18), 2, T0),
                pending(newAccount(19), 2, T0));
        provisioning.setMode(Mode.UNAVAILABLE);
        at(T0.plus(minutes(1)));
        reconcile.runBudget = Duration.ofNanos(1);

        assertThat(reconcile.run().retried()).isEqualTo(1);
        assertThat(reconcile.run().retried()).isEqualTo(1);
        assertThat(reconcile.run().retried()).isEqualTo(1);
        assertThat(reconcile.run().retried()).isZero();
        assertThat(refs).allSatisfy(ref -> assertThat(row(ref)).containsEntry("attempts", 1));
    }

    // ------------------------------------------------------------------ helpers

    private interface Run {
        Object call(int i) throws Exception;
    }

    private void parallel(int n, Run run) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<Object> task = () -> {
                    start.await();
                    return run.call(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void at(Instant instant) {
        InboundTestClock.CLOCK.set(instant);
    }

    private static Duration minutes(long n) {
        return Duration.ofMinutes(n);
    }

    /** PENDING, restore outcome unknown, first reconcile 1 min after creation. */
    private String pending(String accountRef, int hours, Instant createdAt) {
        return insert(accountRef, "PENDING", hours, createdAt, null, null, createdAt.plus(minutes(1)), 0);
    }

    /** ACTIVE since {@code activatedAt} for {@code hours}. */
    private String active(String accountRef, int hours, Instant activatedAt) {
        return insert(accountRef, "ACTIVE", hours, activatedAt, activatedAt, activatedAt.plus(Duration.ofHours(hours)),
                null, 0);
    }

    private String insert(String accountRef, String status, int hours, Instant createdAt, Instant activatedAt,
                          Instant expiresAt, Instant nextAttemptAt, int attempts) {
        String reference = "REA-20300115-" + (970000 + REFS.incrementAndGet());
        jdbc.update("""
                INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours,
                                                   reason_text, identity_check_id, requested_by_user_id, status,
                                                   failure_reason, end_reason, provider_ref, activated_at, expires_at,
                                                   attempts, next_attempt_at, created_at, updated_at)
                SELECT :ref, a.id, 'NON_PAYMENT', JSON_ARRAY(s.msisdn), :hours, 'Job test reactivation', c.id, u.id,
                       :status, IF(:status = 'PENDING', 'provider_unknown', NULL),
                       IF(:status = 'EXPIRING', 'expired', NULL), IF(:activated IS NULL, NULL, CONCAT('PRV-', :ref)),
                       :activated, :expires, :attempts, :next, :created, :created
                FROM accounts a
                JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
                JOIN identity_checks c ON c.customer_id = a.customer_id
                JOIN users u ON u.username = 'T-IN-REACT'
                WHERE a.account_ref = :account
                """, new MapSqlParameterSource()
                .addValue("ref", reference)
                .addValue("hours", hours)
                .addValue("status", status)
                .addValue("activated", activatedAt == null ? null : utc(activatedAt))
                .addValue("expires", expiresAt == null ? null : utc(expiresAt))
                .addValue("attempts", attempts)
                .addValue("next", nextAttemptAt == null ? null : utc(nextAttemptAt))
                .addValue("created", utc(createdAt))
                .addValue("account", accountRef));
        return reference;
    }

    /** {@code CUST-T-Jnnn}: ACTIVE customer, account SUSPENDED NON_PAYMENT, a SUSPENDED primary, a VERIFIED check. */
    private String newAccount(int n) {
        String suffix = String.format("%03d", n);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerRef", "CUST-T-J" + suffix)
                .addValue("accountRef", "ACC-T-J" + suffix)
                .addValue("msisdn", msisdn(n))
                .addValue("t", utc(T0.minus(Duration.ofDays(3))));
        jdbc.update("""
                INSERT INTO customers (customer_ref, full_name, billing_address, status, created_at, updated_at)
                VALUES (:customerRef, 'Test Job Customer',
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
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                SELECT u.id, c.id, 'KBA', 'VERIFIED', 1, :t, :t, :t, :t
                FROM customers c JOIN users u ON u.username = 'T-IN-REACT'
                WHERE c.customer_ref = :customerRef
                """, params);
        return "ACC-T-J" + suffix;
    }

    private static String msisdn(int n) {
        return String.format("+9199004000%02d", n);
    }

    private Map<String, Object> row(String reference) {
        return jdbc.queryForMap("""
                SELECT status, failure_reason, end_reason, provider_ref, activated_at, expires_at, ended_at, attempts,
                       next_attempt_at, needs_attention, overdue_alerted_at, open_account_id
                FROM reactivation_requests WHERE reference = :ref
                """, Map.of("ref", reference));
    }

    private List<Operation> operations(String reference) {
        return provisioning.callsFor(reference).stream().map(MockProvisioningClient.Call::operation).toList();
    }

    private List<String> notifications(String reference) {
        return jdbc.queryForList("""
                SELECT CONCAT(template, '/', channel) FROM notifications WHERE source_ref = :ref ORDER BY id
                """, Map.of("ref", reference), String.class);
    }

    private long audits(String action, String reference) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = :action AND entity_id = :ref",
                Map.of("action", action, "ref", reference), Long.class);
    }

    private String auditActor(String action, String reference) {
        return jdbc.queryForObject("""
                SELECT actor_username FROM audit_events WHERE action = :action AND entity_id = :ref
                """, Map.of("action", action, "ref", reference), String.class);
    }

    private Map<String, Object> details(String action, String reference) {
        String json = jdbc.queryForObject("""
                SELECT CAST(details AS CHAR) FROM audit_events WHERE action = :action AND entity_id = :ref
                ORDER BY id DESC LIMIT 1
                """, Map.of("action", action, "ref", reference), String.class);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String accountStatus(String accountRef) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE account_ref = :ref", Map.of("ref", accountRef),
                String.class);
    }

    private static Instant instant(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
