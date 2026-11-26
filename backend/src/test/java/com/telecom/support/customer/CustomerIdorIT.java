package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cross-customer access (IDOR) for slice 02 (design 02-customer backend R1, §4, §9; CUS-01.3, NFR-INTEG). Customer 1
 * ({@code +919900000001}) uses customer 2's ids and references: profile (a request-supplied customer id is ignored),
 * verification id (E4), subscription id (E6, E7, E9, E12), invoice id (E15) and payment reference (E16, E17) → 404 and
 * nothing changed; the lists E5, E8, E11, E13, E14 never show customer 2's data. Cases created by other ITs persist
 * (append-only), so counts are compared before and after, never absolute.
 */
class CustomerIdorIT extends AbstractMySqlIT {

    private static final String ME = "/api/v1/customers/me";
    private static final String XSRF = "idor-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie session;
    private long otherCustomer;
    private long otherSubscription;
    private long otherOpenInvoice;
    private long otherPaidInvoice;
    private String otherPayment;
    private String otherCase;

    @BeforeEach
    void setUp() throws Exception {
        session = login("+919900000001");
        otherCustomer = id("SELECT id FROM customers WHERE customer_ref = 'CUST-T-0002'");
        otherSubscription = id("SELECT id FROM subscriptions WHERE msisdn = '+919900000002'");
        otherOpenInvoice = id("SELECT id FROM invoices WHERE invoice_ref = 'INV-T-0102'");
        otherPaidInvoice = id("SELECT id FROM invoices WHERE invoice_ref = 'INV-T-0101'");
        otherPayment = string("SELECT reference FROM payments WHERE gateway_session_id = 'test-sess-0004'");
        otherCase = string("""
                SELECT c.reference FROM cases c JOIN accounts a ON a.id = c.account_id
                WHERE a.account_ref = 'ACC-T-0002' AND c.subject = 'Wrong roaming charge'""");
    }

    // ------------------------------------------------------------------ profile

    @Test
    void profileIsAlwaysTheCallersOwnWhateverCustomerIdIsSent() throws Exception {
        Map<String, Object> other = customerRow(otherCustomer);

        Map<String, Object> read = json(mvc.perform(get(ME + "/profile").param("customerId", "" + otherCustomer)
                .cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(read.get("customerRef")).isEqualTo("CUST-T-0001");

        Map<String, Object> update = new HashMap<>();
        update.put("aliasName", read.get("aliasName"));    // unchanged value: only the target is under test
        update.put("version", read.get("version"));
        update.put("customerId", otherCustomer);
        Map<String, Object> updated = json(send(patch(ME + "/profile"), update).andExpect(status().isOk()).andReturn());

        assertThat(updated.get("customerRef")).isEqualTo("CUST-T-0001");
        assertThat(customerRow(otherCustomer)).isEqualTo(other);
    }

    // ------------------------------------------------------------------ E4

    @Test
    void anotherCustomersVerificationIs404AndUntouched() throws Exception {
        long verification = insertPendingVerification(otherCustomer);
        try {
            String path = ME + "/contact-verifications/" + verification + "/confirm";
            assertProblem(send(post(path), Map.of("code", "123456")).andReturn(), 404, "not-found", path);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT attempts, status FROM contact_verifications WHERE id = :id", Map.of("id", verification));
            assertThat(((Number) row.get("attempts")).intValue()).isZero();
            assertThat(row.get("status")).isEqualTo("PENDING");
        } finally {
            // keep customer 2's starts-per-hour budget for ContactVerificationIT
            jdbc.update("DELETE FROM contact_verifications WHERE id = :id", Map.of("id", verification));
        }
    }

    // ------------------------------------------------------------------ subscriptions: E6, E7, E9, E12

    @Test
    void anotherCustomersSubscriptionIs404AndNothingIsWritten() throws Exception {
        long plansBefore = count("SELECT COUNT(*) FROM plan_changes WHERE subscription_id = :id", otherSubscription);
        long claimsBefore = count("SELECT COUNT(*) FROM promotion_claims WHERE subscription_id = :id",
                otherSubscription);
        long casesBefore = count("SELECT COUNT(*) FROM cases WHERE subscription_id = :id", otherSubscription);
        long ownAccount = id("SELECT id FROM accounts WHERE account_ref = 'ACC-T-0001'");
        long ownCasesBefore = count("SELECT COUNT(*) FROM cases WHERE account_id = :id", ownAccount);

        String options = ME + "/subscriptions/" + otherSubscription + "/plan-options";
        assertProblem(mvc.perform(get(options).cookie(session)).andReturn(), 404, "not-found", options);

        String changes = ME + "/subscriptions/" + otherSubscription + "/plan-changes";
        assertProblem(send(post(changes), Map.of("targetPlanCode", "POST_399", "expectedEffectiveDate", "2030-01-15",
                "expectedCharge", "0.00")).andReturn(), 404, "not-found", changes);

        String claims = ME + "/promotions/T_DATA_BOOST/claims";
        String hash = PromotionEligibilityService.termsHash(string(
                "SELECT terms FROM promotions WHERE code = 'T_DATA_BOOST'"));
        assertProblem(send(post(claims), Map.of("subscriptionId", otherSubscription, "acceptTerms", true,
                "termsHash", hash)).andReturn(), 404, "not-found", claims);

        Map<String, Object> caseBody = new LinkedHashMap<>();
        caseBody.put("categoryCode", "NETWORK");
        caseBody.put("subcategoryCode", "SLOW_DATA");
        caseBody.put("subscriptionId", otherSubscription);
        caseBody.put("subject", "Not my number");
        caseBody.put("description", "Trying another customer's subscription");
        caseBody.put("preferredContact", "SMS");
        assertProblem(send(post(ME + "/cases"), caseBody).andReturn(), 404, "not-found", ME + "/cases");

        assertThat(count("SELECT COUNT(*) FROM plan_changes WHERE subscription_id = :id", otherSubscription))
                .isEqualTo(plansBefore);
        assertThat(count("SELECT COUNT(*) FROM promotion_claims WHERE subscription_id = :id", otherSubscription))
                .isEqualTo(claimsBefore);
        assertThat(count("SELECT COUNT(*) FROM cases WHERE subscription_id = :id", otherSubscription))
                .isEqualTo(casesBefore);
        assertThat(count("SELECT COUNT(*) FROM cases WHERE account_id = :id", ownAccount)).isEqualTo(ownCasesBefore);
    }

    // ------------------------------------------------------------------ payments: E15, E16, E17

    @Test
    void anotherCustomersInvoiceAndPaymentAre404() throws Exception {
        for (long invoice : new long[] {otherOpenInvoice, otherPaidInvoice}) {
            long before = count("SELECT COUNT(*) FROM payments WHERE invoice_id = :id", invoice);
            assertProblem(send(post(ME + "/payments"), Map.of("invoiceId", invoice, "expectedAmount", "599.00"))
                    .andReturn(), 404, "not-found", ME + "/payments");
            assertThat(count("SELECT COUNT(*) FROM payments WHERE invoice_id = :id", invoice)).isEqualTo(before);
        }

        String payment = ME + "/payments/" + otherPayment;
        assertProblem(mvc.perform(get(payment).cookie(session)).andReturn(), 404, "not-found", payment);
        assertProblem(mvc.perform(get(payment + "/receipt").cookie(session)).andReturn(), 404, "not-found",
                payment + "/receipt");
    }

    // ------------------------------------------------------------------ lists: E5, E8, E11, E13, E14

    @Test
    void listsNeverShowAnotherCustomersData() throws Exception {
        List<Map<String, Object>> subscriptions = jsonList(mvc.perform(get(ME + "/subscriptions").cookie(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(subscriptions).isNotEmpty();
        assertThat(subscriptions).extracting(s -> ((Number) s.get("id")).longValue())
                .doesNotContain(otherSubscription);

        List<Map<String, Object>> promotions = jsonList(mvc.perform(get(ME + "/promotions").cookie(session))
                .andExpect(status().isOk()).andReturn());
        for (Map<String, Object> promotion : promotions) {
            assertThat(longs(promotion.get("eligibleSubscriptionIds"))).doesNotContain(otherSubscription);
            assertThat(longs(promotion.get("claimedSubscriptionIds"))).doesNotContain(otherSubscription);
        }

        Map<String, Object> cases = json(mvc.perform(get(ME + "/cases").param("size", "100").cookie(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(cases)).extracting(c -> c.get("reference")).isNotEmpty().doesNotContain(otherCase);
        Map<String, Object> search = json(mvc.perform(get(ME + "/cases").param("reference", otherCase)
                .cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(items(search)).isEmpty();

        List<Map<String, Object>> invoices = jsonList(mvc.perform(get(ME + "/invoices").cookie(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(invoices).extracting(i -> i.get("invoiceRef")).isNotEmpty()
                .doesNotContain("INV-T-0101", "INV-T-0102");

        Map<String, Object> payments = json(mvc.perform(get(ME + "/payments").param("size", "100").cookie(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(payments)).extracting(p -> p.get("reference")).isNotEmpty().doesNotContain(otherPayment);
    }

    // ------------------------------------------------------------------ helpers

    private long insertPendingVerification(long customerId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO contact_verifications (customer_id, channel, new_value, code_hmac, attempts, status,
                                                   expires_at, created_at, updated_at)
                VALUES (:customerId, 'EMAIL', 'idor.probe@example.test', :hmac, 0, 'PENDING',
                        UTC_TIMESTAMP(6) + INTERVAL 10 MINUTE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, new MapSqlParameterSource("customerId", customerId).addValue("hmac", "a".repeat(64)), keys,
                new String[] {"id"});
        return keys.getKey().longValue();
    }

    private Map<String, Object> customerRow(long id) {
        return jdbc.queryForMap("SELECT alias_name, email, secondary_msisdn, version, updated_at FROM customers "
                + "WHERE id = :id", Map.of("id", id));
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return mvc.perform(builder.cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", "idor-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    private String string(String sql) {
        return jdbc.queryForObject(sql, Map.of(), String.class);
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Map.of("id", id), Long.class);
    }

    private static List<Long> longs(Object values) {
        @SuppressWarnings("unchecked")
        List<Number> numbers = (List<Number>) values;
        return numbers.stream().map(Number::longValue).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> jsonList(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.62." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with((MockHttpServletRequest r) -> {
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
