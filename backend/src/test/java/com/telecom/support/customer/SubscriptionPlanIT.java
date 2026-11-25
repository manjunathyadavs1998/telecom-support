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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * E5 {@code GET /api/v1/customers/me/subscriptions} end to end (design 02-customer backend R1, R4, §4, §9;
 * CUS-03.1–03.3, BR-10). Test data: R__test_users_02_customer.sql. E6/E7 cases are added by B9/B10.
 */
class SubscriptionPlanIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/subscriptions";
    private static final String XSRF = "subscription-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /** Test data has no plan changes; remove the ones a test inserted. */
    @AfterEach
    void removePlanChanges() {
        jdbc.update("""
                DELETE pc FROM plan_changes pc
                JOIN subscriptions s ON s.id = pc.subscription_id
                WHERE s.msisdn IN ('+919900000001', '+919900000011', '+919900000002')
                """, Map.of());
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
