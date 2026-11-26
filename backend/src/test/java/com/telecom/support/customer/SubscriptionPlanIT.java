package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.customer.MockPlanProvisioning.Applied;
import com.telecom.support.customer.MockPlanProvisioning.Mode;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E5 {@code GET /api/v1/customers/me/subscriptions}, E6 plan options and E7 plan change + {@link PlanChangeApplyJob}
 * end to end (design 02-customer backend R1, R4–R6, R16, §4, §7, §8, §9; CUS-03, BR-08, BR-10, NFR-REL). Test data:
 * R__test_users_02_customer.sql (customer 1 billing day 5: POST_399 primary + PRE_199; customer 2 day 15: POST_599;
 * customer 3 CLOSED). Time moves only through the {@link MutableClock}; T0 is 2026-05-04 11:30 Asia/Kolkata.
 */
class SubscriptionPlanIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/subscriptions";
    private static final String XSRF = "subscription-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final Instant T0 = Instant.parse("2026-05-04T06:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockPlanProvisioning provisioning;

    @Autowired
    PlanChangeApplyJob job;

    static Clock fixedClock() {
        return CLOCK;
    }

    @BeforeEach
    void reset() {
        CLOCK.set(T0);
        provisioning.reset();
    }

    /** Test data has no plan changes; remove the ones a test inserted and restore plans and states it changed. */
    @AfterEach
    void restore() {
        provisioning.reset();
        jdbc.update("""
                DELETE pc FROM plan_changes pc
                JOIN subscriptions s ON s.id = pc.subscription_id
                WHERE s.msisdn IN ('+919900000001', '+919900000011', '+919900000002')
                """, Map.of());
        jdbc.update("""
                UPDATE subscriptions s
                JOIN (SELECT '+919900000001' AS msisdn, 'POST_399' AS code
                      UNION ALL SELECT '+919900000011', 'PRE_199'
                      UNION ALL SELECT '+919900000002', 'POST_599') o ON o.msisdn = s.msisdn
                JOIN plans p ON p.code = o.code
                SET s.plan_id = p.id, s.status = 'ACTIVE'
                WHERE s.status <> 'TERMINATED'
                """, Map.of());
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE account_ref IN ('ACC-T-0001', 'ACC-T-0002')", Map.of());
    }

    // ------------------------------------------------------------------ E5

    @Test
    void listsOwnSubscriptionsPrimaryFirstWithPlanSummaryAndBenefits() throws Exception {
        MvcResult result = mvc.perform(get(PATH).cookie(login("+919900000001"))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        List<Map<String, Object>> body = json(result);
        assertThat(body).hasSize(2);

        Map<String, Object> primary = body.get(0);
        assertThat(primary).containsOnlyKeys("id", "msisdn", "primary", "status", "billingCycleDay", "plan",
                "pendingPlanChange");
        assertThat(((Number) primary.get("id")).longValue()).isEqualTo(subscriptionId("+919900000001"));
        assertThat(primary.get("msisdn")).isEqualTo("+919900000001");
        assertThat(primary.get("primary")).isEqualTo(true);
        assertThat(primary.get("status")).isEqualTo("ACTIVE");
        assertThat(primary.get("billingCycleDay")).isEqualTo(5);
        assertThat(primary.get("pendingPlanChange")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) primary.get("plan");
        assertThat(plan).containsOnlyKeys("code", "name", "planType", "monthlyRental", "description", "benefits");
        assertThat(plan.get("code")).isEqualTo("POST_399");
        assertThat(plan.get("name")).isEqualTo("Postpaid 399");
        assertThat(plan.get("planType")).isEqualTo("POSTPAID");
        assertThat(plan.get("monthlyRental")).isEqualTo("399.00");
        assertThat(plan.get("description")).isEqualTo("75 GB, unlimited calls");
        assertThat(plan.get("benefits")).isEqualTo(List.of(
                Map.of("type", "DATA", "label", "Data", "value", "75 GB/month", "key", true),
                Map.of("type", "VOICE", "label", "Calls", "value", "Unlimited", "key", true),
                Map.of("type", "SMS", "label", "SMS", "value", "100/day", "key", false)));

        Map<String, Object> second = body.get(1);
        assertThat(second.get("msisdn")).isEqualTo("+919900000011");
        assertThat(second.get("primary")).isEqualTo(false);
        assertThat(second.get("billingCycleDay")).isEqualTo(5);
        assertThat(planOf(second).get("code")).isEqualTo("PRE_199");
        assertThat(planOf(second).get("monthlyRental")).isEqualTo("199.00");
    }

    @Test
    void showsOnlyTheScheduledPlanChange() throws Exception {
        long secondary = subscriptionId("+919900000011");
        insertPlanChange(secondary, "PRE_199", "PRE_449", LocalDate.now().minusMonths(2), "CANCELLED");
        insertPlanChange(secondary, "PRE_199", "PRE_449", LocalDate.now().minusMonths(1), "APPLIED");
        long scheduled = insertPlanChange(secondary, "PRE_199", "PRE_299", LocalDate.now().plusDays(10), "SCHEDULED");

        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000001")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get(0).get("pendingPlanChange")).isNull();
        assertThat(body.get(1).get("pendingPlanChange")).isEqualTo(Map.of(
                "id", (int) scheduled,
                "fromPlanCode", "PRE_199",
                "toPlanCode", "PRE_299",
                "effectiveDate", LocalDate.now().plusDays(10).toString(),
                "charge", "0.00",
                "status", "SCHEDULED"));
        assertThat(planOf(body.get(1)).get("code")).isEqualTo("PRE_199");
    }

    @Test
    void eachCustomerSeesOnlyTheirOwnSubscriptions() throws Exception {
        insertPlanChange(subscriptionId("+919900000011"), "PRE_199", "PRE_299", LocalDate.now().plusDays(10),
                "SCHEDULED");

        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000002")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("msisdn")).isEqualTo("+919900000002");
        assertThat(body.get(0).get("billingCycleDay")).isEqualTo(15);
        assertThat(body.get(0).get("pendingPlanChange")).isNull();
        assertThat(planOf(body.get(0)).get("code")).isEqualTo("POST_599");
    }

    @Test
    void closedAccountStillListsItsTerminatedSubscription() throws Exception {
        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000003")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("msisdn")).isEqualTo("+919900000003");
        assertThat(body.get(0).get("status")).isEqualTo("TERMINATED");
        assertThat(body.get(0).get("billingCycleDay")).isEqualTo(1);
    }

    @Test
    void listIsUnauthenticatedForAnonymous() throws Exception {
        assertProblem(mvc.perform(get(PATH)).andReturn(), 401, "unauthenticated", PATH);
    }

    @Test
    void listIsForbiddenForStaffAndUnlinkedCustomers() throws Exception {
        // INBOUND_AGENT holds plan.read but is not a customer (§4)
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(mvc.perform(get(PATH).cookie(login(username))).andReturn(), 403, "forbidden", PATH);
        }
    }

    // ------------------------------------------------------------------ E6

    @Test
    void optionsAreHigherRentalSameTypeActivePlansWithQuote() throws Exception {
        Cookie session = login("+919900000001");

        List<Map<String, Object>> postpaid = json(mvc.perform(get(options(subscriptionId("+919900000001")))
                .cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(postpaid).hasSize(1); // POST_349 is RETIRED and cheaper, prepaid plans are another type
        Map<String, Object> option = postpaid.get(0);
        assertThat(option).containsOnlyKeys("plan", "rentalDifference", "effectiveDate", "charge");
        assertThat(planOf(option).get("code")).isEqualTo("POST_599");
        assertThat(planOf(option).get("monthlyRental")).isEqualTo("599.00");
        assertThat((List<?>) planOf(option).get("benefits")).isNotEmpty();
        assertThat(option.get("rentalDifference")).isEqualTo("200.00");
        assertThat(option.get("effectiveDate")).isEqualTo("2026-05-05");
        assertThat(option.get("charge")).isEqualTo("0.00");

        List<Map<String, Object>> prepaid = json(mvc.perform(get(options(subscriptionId("+919900000011")))
                .cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(prepaid).extracting(o -> planOf(o).get("code")).containsExactly("PRE_299", "PRE_449");
        assertThat(prepaid).extracting(o -> o.get("rentalDifference")).containsExactly("100.00", "250.00");
    }

    @Test
    void effectiveDateIsStrictlyAfterTodayInKolkata() throws Exception {
        // each clock jump is past the session's absolute timeout, so sign in again after it
        String path = options(subscriptionId("+919900000001"));

        CLOCK.set(Instant.parse("2026-05-04T18:29:59Z")); // 23:59:59 on the 4th in Kolkata
        assertThat(json(mvc.perform(get(path).cookie(login("+919900000001"))).andReturn()).get(0).get("effectiveDate"))
                .isEqualTo("2026-05-05");

        CLOCK.set(Instant.parse("2026-05-04T18:30:00Z")); // 00:00 on the 5th = billing day → next month
        assertThat(json(mvc.perform(get(path).cookie(login("+919900000001"))).andReturn()).get(0).get("effectiveDate"))
                .isEqualTo("2026-06-05");

        CLOCK.set(Instant.parse("2026-12-20T06:00:00Z")); // after the day in December → next year
        assertThat(json(mvc.perform(get(path).cookie(login("+919900000001"))).andReturn()).get(0).get("effectiveDate"))
                .isEqualTo("2027-01-05");
    }

    @Test
    void noOptionsForTopPlanPendingChangeOrTerminatedSubscription() throws Exception {
        assertThat(json(mvc.perform(get(options(subscriptionId("+919900000002"))).cookie(login("+919900000002")))
                .andExpect(status().isOk()).andReturn())).isEmpty();

        long secondary = subscriptionId("+919900000011");
        insertPlanChange(secondary, "PRE_199", "PRE_299", LocalDate.parse("2026-05-05"), "SCHEDULED");
        assertThat(json(mvc.perform(get(options(secondary)).cookie(login("+919900000001")))
                .andExpect(status().isOk()).andReturn())).isEmpty();

        long terminated = terminatedSubscriptionId();
        assertThat(json(mvc.perform(get(options(terminated)).cookie(login("+919900000003")))
                .andExpect(status().isOk()).andReturn())).isEmpty();
    }

    @Test
    void optionsOfAnotherCustomersSubscriptionAreNotFound() throws Exception {
        Cookie session = login("+919900000001");
        String path = options(subscriptionId("+919900000002"));
        assertProblem(mvc.perform(get(path).cookie(session)).andReturn(), 404, "not-found", path);
        String unknown = options(999_999_999L);
        assertProblem(mvc.perform(get(unknown).cookie(session)).andReturn(), 404, "not-found", unknown);
    }

    @Test
    void optionsNeedAuthenticationAndACustomer() throws Exception {
        String path = options(subscriptionId("+919900000001"));
        assertProblem(mvc.perform(get(path)).andReturn(), 401, "unauthenticated", path);
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(mvc.perform(get(path).cookie(login(username))).andReturn(), 403, "forbidden", path);
        }
    }

    // ------------------------------------------------------------------ E7

    @Test
    void schedulesTheQuotedChangeAndAudits() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000001");
        MvcResult result = change(session, sub, "k-schedule", "POST_599", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn();

        Map<String, Object> body = jsonObject(result);
        long id = ((Number) body.get("id")).longValue();
        assertThat(body).isEqualTo(Map.of("id", (int) id, "fromPlanCode", "POST_399", "toPlanCode", "POST_599",
                "effectiveDate", "2026-05-05", "charge", "0.00", "status", "SCHEDULED"));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT pc.subscription_id, pc.status, pc.effective_date, pc.charge_amount, pc.apply_attempts,
                       u.username
                FROM plan_changes pc JOIN users u ON u.id = pc.requested_by_user_id WHERE pc.id = :id
                """, Map.of("id", id));
        assertThat(((Number) row.get("subscription_id")).longValue()).isEqualTo(sub);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(row.get("effective_date").toString()).isEqualTo("2026-05-05");
        assertThat(row.get("charge_amount").toString()).isEqualTo("0.00");
        assertThat(((Number) row.get("apply_attempts")).intValue()).isZero();
        assertThat(row.get("username")).isEqualTo("+919900000001");
        // only scheduled: the subscription keeps its plan until the job applies it (BR-08)
        assertThat(planCode(sub)).isEqualTo("POST_399");

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, entity_type, entity_id, outcome, details FROM audit_events
                WHERE action = 'PLAN_CHANGE_SCHEDULED' AND correlation_id = :cid
                """, Map.of("cid", result.getResponse().getHeader("X-Correlation-Id")));
        assertThat(audit.get("actor_username")).isEqualTo("+919900000001");
        assertThat(audit.get("entity_type")).isEqualTo("SUBSCRIPTION");
        assertThat(audit.get("entity_id")).isEqualTo(String.valueOf(sub));
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(details(audit)).isEqualTo(Map.of("fromPlan", "POST_399", "toPlan", "POST_599",
                "effectiveDate", "2026-05-05", "charge", "0.00"));

        // E5 now shows the pending change
        List<Map<String, Object>> subs = json(mvc.perform(get(PATH).cookie(session)).andReturn());
        assertThat(subs.get(0).get("pendingPlanChange")).isEqualTo(body);
    }

    @Test
    void quoteMismatchOrIneligibleTargetIs422() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000011");
        String path = changes(sub);

        assertProblem(change(session, sub, "k-m1", "PRE_299", "2026-06-05", "0.00").andReturn(), 422,
                "business-rule", path);
        assertProblem(change(session, sub, "k-m2", "PRE_299", "2026-05-05", "10.00").andReturn(), 422,
                "business-rule", path);
        // strictly after today: on the billing day itself that day is no longer the quote
        CLOCK.set(Instant.parse("2026-05-05T06:00:00Z"));
        assertProblem(change(login("+919900000001"), sub, "k-m3", "PRE_299", "2026-05-05", "0.00").andReturn(), 422,
                "business-rule", path);
        CLOCK.set(T0);
        session = login("+919900000001");
        for (String target : List.of("PRE_199", "POST_599", "POST_349", "NOPE")) { // same, other type, retired, unknown
            assertProblem(change(session, sub, "k-t-" + target, target, "2026-05-05", "0.00").andReturn(), 422,
                    "business-rule", path);
        }
        assertThat(scheduledCount(sub)).isZero();
    }

    @Test
    void secondScheduledChangeIs422() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000011");
        change(session, sub, "k-first", "PRE_299", "2026-05-05", "0.00").andExpect(status().isCreated());

        assertProblem(change(session, sub, "k-second", "PRE_449", "2026-05-05", "0.00").andReturn(), 422,
                "business-rule", changes(sub));
        assertThat(scheduledCount(sub)).isEqualTo(1);
    }

    @Test
    void suspendedOrClosedAccountAndInactiveSubscriptionCannotChangePlan() throws Exception {
        // OQ 12: a SUSPENDED account cannot change plan (customer 1's account, otherwise eligible)
        long sub = subscriptionId("+919900000011");
        Cookie session = login("+919900000001");
        jdbc.update("UPDATE accounts SET status = 'SUSPENDED' WHERE account_ref = 'ACC-T-0001'", Map.of());
        assertProblem(change(session, sub, "k-susp", "PRE_299", "2026-05-05", "0.00").andReturn(), 422,
                "business-rule", changes(sub));
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE account_ref = 'ACC-T-0001'", Map.of());

        long terminated = terminatedSubscriptionId();
        assertProblem(change(login("+919900000003"), terminated, "k-closed", "PRE_299", "2026-06-01", "0.00")
                .andReturn(), 422, "business-rule", changes(terminated));

        jdbc.update("UPDATE subscriptions SET status = 'SUSPENDED' WHERE id = :id", Map.of("id", sub));
        assertProblem(change(session, sub, "k-subsusp", "PRE_299", "2026-05-05", "0.00").andReturn(), 422,
                "business-rule", changes(sub));
        assertThat(scheduledCount(sub)).isZero();
    }

    @Test
    void replayWithTheSameKeyReturnsTheSame201() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000011");
        Map<String, Object> first = jsonObject(change(session, sub, "k-replay", "PRE_449", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());
        Map<String, Object> second = jsonObject(change(session, sub, "k-replay", "PRE_449", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_changes WHERE subscription_id = :sub",
                Map.of("sub", sub), Long.class)).isEqualTo(1);
    }

    @Test
    void sameKeyAndBodyOnAnotherSubscriptionIsAnIdempotencyMismatch() throws Exception {
        Cookie session = login("+919900000001");
        long secondary = subscriptionId("+919900000011");
        long primary = subscriptionId("+919900000001");
        change(session, secondary, "k-path", "PRE_299", "2026-05-05", "0.00").andExpect(status().isCreated());

        assertProblem(change(session, primary, "k-path", "PRE_299", "2026-05-05", "0.00").andReturn(), 422,
                "idempotency-mismatch", changes(primary));
        assertThat(scheduledCount(primary)).isZero();
    }

    @Test
    void anotherCustomersSubscriptionIsNotFound() throws Exception {
        long other = subscriptionId("+919900000002");
        assertProblem(change(login("+919900000001"), other, "k-idor", "POST_599", "2026-05-15", "0.00").andReturn(),
                404, "not-found", changes(other));
        assertThat(scheduledCount(other)).isZero();
    }

    @Test
    void missingKeyOrInvalidBodyIs400() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000011");
        String path = changes(sub);
        assertProblem(mvc.perform(post(path).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(body("PRE_299", "2026-05-05", "0.00"))).andReturn(),
                400, "bad-request", path);
        assertProblem(change(session, sub, "k-bad", "PRE_299", "2026-05-05", "0").andReturn(), 400, "validation",
                path);
        assertThat(scheduledCount(sub)).isZero();
    }

    @Test
    void changeNeedsAuthenticationAndACustomer() throws Exception {
        long sub = subscriptionId("+919900000001");
        String path = changes(sub);
        assertProblem(mvc.perform(post(path).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .header("Idempotency-Key", "k-anon").contentType(MediaType.APPLICATION_JSON)
                        .content(body("POST_599", "2026-05-05", "0.00"))).andReturn(),
                401, "unauthenticated", path);
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(change(login(username), sub, "k-403-" + username, "POST_599", "2026-05-05", "0.00")
                    .andReturn(), 403, "forbidden", path);
        }
        assertThat(scheduledCount(sub)).isZero();
    }

    // ------------------------------------------------------------------ apply job

    @Test
    void jobAppliesDueChangesAfterProvisioningConfirms() throws Exception {
        long sub = subscriptionId("+919900000001");
        long id = id(change(login("+919900000001"), sub, "k-job", "POST_599", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());

        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 0)); // not due on the 4th
        assertThat(planCode(sub)).isEqualTo("POST_399");

        CLOCK.set(Instant.parse("2026-05-04T18:35:00Z")); // 00:05 on the 5th in Kolkata
        long appliedBefore = auditCount("PLAN_CHANGE_APPLIED", sub);
        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(1, 0, 0));

        assertThat(planCode(sub)).isEqualTo("POST_599");
        assertThat(changeStatus(id)).isEqualTo("APPLIED");
        assertThat(provisioning.applied()).containsExactly(
                new Applied("+919900000001", "POST_599", LocalDate.parse("2026-05-05")));
        assertThat(auditCount("PLAN_CHANGE_APPLIED", sub)).isEqualTo(appliedBefore + 1);
        Map<String, Object> audit = lastAudit("PLAN_CHANGE_APPLIED", sub);
        assertThat(audit.get("actor_user_id")).isNull();
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(details(audit)).containsEntry("toPlan", "POST_599");

        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 0)); // nothing left
        assertThat(provisioning.applied()).hasSize(1);
    }

    @Test
    void jobCancelsWhenTheGuardFails() throws Exception {
        long sub = subscriptionId("+919900000011");
        long id = id(change(login("+919900000001"), sub, "k-guard", "PRE_299", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());
        jdbc.update("UPDATE subscriptions SET status = 'SUSPENDED' WHERE id = :id", Map.of("id", sub));

        CLOCK.set(Instant.parse("2026-05-05T06:00:00Z"));
        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 1, 0));

        assertThat(changeStatus(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM plan_changes WHERE id = :id", Map.of("id", id),
                String.class)).isEqualTo(PlanChangeApplyJob.GUARD_FAILED_REASON);
        assertThat(planCode(sub)).isEqualTo("PRE_199");
        assertThat(provisioning.applied()).isEmpty();
        Map<String, Object> audit = lastAudit("PLAN_CHANGE_CANCELLED", sub);
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(details(audit)).containsEntry("reason", PlanChangeApplyJob.GUARD_FAILED_REASON);
    }

    @Test
    void provisioningFailureKeepsTheChangeScheduledAndRetriesNextRun() throws Exception {
        long sub = subscriptionId("+919900000011");
        long id = id(change(login("+919900000001"), sub, "k-fail", "PRE_449", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());
        CLOCK.set(Instant.parse("2026-05-05T06:00:00Z"));
        provisioning.setMode(Mode.FAIL);
        long failedBefore = auditCount("PLAN_CHANGE_APPLY_FAILED", sub);

        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 1));

        assertThat(changeStatus(id)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT apply_attempts FROM plan_changes WHERE id = :id", Map.of("id", id),
                Integer.class)).isEqualTo(1);
        assertThat(planCode(sub)).isEqualTo("PRE_199"); // the plan switch was rolled back
        assertThat(auditCount("PLAN_CHANGE_APPLY_FAILED", sub)).isEqualTo(failedBefore + 1);
        Map<String, Object> audit = lastAudit("PLAN_CHANGE_APPLY_FAILED", sub);
        assertThat(audit.get("outcome")).isEqualTo("FAILURE");
        assertThat(details(audit)).containsEntry("reason", PlanChangeApplyJob.PROVIDER_FAILED_REASON);

        provisioning.setMode(Mode.NORMAL);
        CLOCK.set(Instant.parse("2026-05-06T06:00:00Z"));
        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(1, 0, 0));
        assertThat(changeStatus(id)).isEqualTo("APPLIED");
        assertThat(planCode(sub)).isEqualTo("PRE_449");
    }

    @Test
    void thirdProvisioningFailureCancelsTheChangeAndFreesTheSubscription() throws Exception {
        long sub = subscriptionId("+919900000011");
        long id = id(change(login("+919900000001"), sub, "k-fail3", "PRE_449", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());
        provisioning.setMode(Mode.FAIL);
        long failedBefore = auditCount("PLAN_CHANGE_APPLY_FAILED", sub);
        long cancelledBefore = auditCount("PLAN_CHANGE_CANCELLED", sub);

        for (String day : List.of("2026-05-05", "2026-05-06")) {
            CLOCK.set(Instant.parse(day + "T06:00:00Z"));
            assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 1));
            assertThat(changeStatus(id)).isEqualTo("SCHEDULED");
        }
        assertThat(auditCount("PLAN_CHANGE_CANCELLED", sub)).isEqualTo(cancelledBefore);

        CLOCK.set(Instant.parse("2026-05-07T06:00:00Z"));
        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 1));

        // erratum E3: the 3rd failed attempt cancels the change with provisioning_failed + PLAN_CHANGE_CANCELLED FAILURE
        assertThat(changeStatus(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("SELECT cancel_reason, apply_attempts FROM plan_changes WHERE id = :id",
                Map.of("id", id))).containsEntry("cancel_reason", PlanChangeApplyJob.PROVIDER_FAILED_REASON)
                .hasEntrySatisfying("apply_attempts", n -> assertThat(((Number) n).intValue()).isEqualTo(3));
        assertThat(planCode(sub)).isEqualTo("PRE_199");
        assertThat(auditCount("PLAN_CHANGE_APPLY_FAILED", sub)).isEqualTo(failedBefore + 3);
        assertThat(auditCount("PLAN_CHANGE_CANCELLED", sub)).isEqualTo(cancelledBefore + 1);
        Map<String, Object> audit = lastAudit("PLAN_CHANGE_CANCELLED", sub);
        assertThat(audit.get("outcome")).isEqualTo("FAILURE");
        assertThat(audit.get("actor_user_id")).isNull();
        assertThat(details(audit)).containsEntry("reason", PlanChangeApplyJob.PROVIDER_FAILED_REASON);

        // no further attempts, and the subscription can be quoted and changed again
        CLOCK.set(Instant.parse("2026-05-08T06:00:00Z"));
        assertThat(job.run()).isEqualTo(new PlanChangeApplyJob.Result(0, 0, 0));
        CLOCK.set(T0);
        Cookie session = login("+919900000001");
        assertThat(json(mvc.perform(get(options(sub)).cookie(session)).andExpect(status().isOk()).andReturn()))
                .isNotEmpty();
        long next = id(change(session, sub, "k-fail3-new", "PRE_449", "2026-05-05", "0.00")
                .andExpect(status().isCreated()).andReturn());
        assertThat(changeStatus(next)).isEqualTo("SCHEDULED");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> planOf(Map<String, Object> subscription) {
        return (Map<String, Object>) subscription.get("plan");
    }

    private long insertPlanChange(long subscriptionId, String fromCode, String toCode, LocalDate effectiveDate,
                                  String status) {
        jdbc.update("""
                INSERT INTO plan_changes (subscription_id, from_plan_id, to_plan_id, effective_date, charge_amount,
                                          status, apply_attempts, requested_by_user_id, created_at, updated_at)
                SELECT :sub, f.id, t.id, :effective, 0.00, :status, 0, u.id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM plans f, plans t, users u
                WHERE f.code = :from AND t.code = :to AND u.username = '+919900000001'
                """, Map.of("sub", subscriptionId, "from", fromCode, "to", toCode, "effective", effectiveDate,
                "status", status));
        return jdbc.queryForObject("SELECT MAX(id) FROM plan_changes WHERE subscription_id = :sub",
                Map.of("sub", subscriptionId), Long.class);
    }

    private long subscriptionId(String msisdn) {
        return jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = :msisdn AND status <> 'TERMINATED'",
                Map.of("msisdn", msisdn), Long.class);
    }

    private static String options(long subscriptionId) {
        return PATH + "/" + subscriptionId + "/plan-options";
    }

    private static String changes(long subscriptionId) {
        return PATH + "/" + subscriptionId + "/plan-changes";
    }

    private String body(String target, String effectiveDate, String charge) throws Exception {
        return objectMapper.writeValueAsString(Map.of("targetPlanCode", target, "expectedEffectiveDate", effectiveDate,
                "expectedCharge", charge));
    }

    private ResultActions change(Cookie session, long subscriptionId, String key, String target, String effectiveDate,
                                 String charge) throws Exception {
        return mvc.perform(post(changes(subscriptionId)).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(target, effectiveDate, charge)));
    }

    private long id(MvcResult result) throws Exception {
        return ((Number) jsonObject(result).get("id")).longValue();
    }

    private long terminatedSubscriptionId() {
        return jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = '+919900000003'", Map.of(),
                Long.class);
    }

    private long scheduledCount(long subscriptionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM plan_changes WHERE subscription_id = :sub AND status = 'SCHEDULED'
                """, Map.of("sub", subscriptionId), Long.class);
    }

    private String changeStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM plan_changes WHERE id = :id", Map.of("id", id), String.class);
    }

    private String planCode(long subscriptionId) {
        return jdbc.queryForObject("""
                SELECT p.code FROM subscriptions s JOIN plans p ON p.id = s.plan_id WHERE s.id = :id
                """, Map.of("id", subscriptionId), String.class);
    }

    private long auditCount(String action, long subscriptionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = :action AND entity_type = 'SUBSCRIPTION' AND entity_id = :id
                """, Map.of("action", action, "id", String.valueOf(subscriptionId)), Long.class);
    }

    private Map<String, Object> lastAudit(String action, long subscriptionId) {
        return jdbc.queryForMap("""
                SELECT actor_user_id, outcome, details FROM audit_events
                WHERE action = :action AND entity_type = 'SUBSCRIPTION' AND entity_id = :id
                ORDER BY id DESC LIMIT 1
                """, Map.of("action", action, "id", String.valueOf(subscriptionId)));
    }

    private Map<String, Object> details(Map<String, Object> audit) throws Exception {
        return objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
    }

    private Map<String, Object> jsonObject(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.23." + (n / 250) + "." + (n % 250 + 1);
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
