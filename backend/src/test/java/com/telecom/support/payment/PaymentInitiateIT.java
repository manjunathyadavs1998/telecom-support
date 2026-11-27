package com.telecom.support.payment;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.payment.MockPaymentGateway.Mode;
import com.telecom.support.payment.PaymentGateway.GatewayPaymentStatus;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Result;
import com.telecom.support.payment.PaymentTransitionService.Source;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E15 initiate payment end to end (design 02-customer backend R12, R16, §3.1, §7, §8, §9, erratum E1; CUS-08.1/08.2/08.4,
 * NFR-REL, NFR-AVAIL). Each test creates its own invoices ({@code INV-T-Z…}, OPEN 399.00 on customer 1's account unless
 * stated) and removes them with their payments afterwards; the mock gateway is reset to NORMAL.
 */
class PaymentInitiateIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/payments";
    private static final String XSRF = "initiate-xsrf-token";
    private static final String CUSTOMER_1 = "+919900000001";
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockPaymentGateway gateway;

    @Autowired
    PaymentTransitionService transitions;

    @Value("${app.payment.return-url-base}")
    String returnUrlBase;

    @AfterEach
    void cleanUp() {
        gateway.reset();
        jdbc.update("""
                DELETE p FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE i.invoice_ref LIKE 'INV-T-Z%'
                """, Map.of());
        jdbc.update("DELETE FROM invoices WHERE invoice_ref LIKE 'INV-T-Z%'", Map.of());
        reinstateAccount("ACC-T-0001");
        jdbc.update("UPDATE customers SET status = 'ACTIVE' WHERE customer_ref = 'CUST-T-0001'", Map.of());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void createsPendingPaymentWithStoredSessionAndAudit() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Map<String, Object> body = request(invoice.id, "399.00");
        body.put("accountId", accountId("ACC-T-0002"));
        body.put("amount", "1.00");
        body.put("returnUrl", "https://evil.example/");

        MvcResult result = initiate(login(CUSTOMER_1), key(), body).andExpect(status().isCreated()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> created = json(result);
        assertThat(created).containsOnlyKeys("reference", "status", "redirectUrl");
        String reference = (String) created.get("reference");
        assertThat(reference).matches("PAY-\\d{8}-\\d{6,}");
        assertThat(created.get("status")).isEqualTo("PENDING");
        String redirectUrl = (String) created.get("redirectUrl");
        assertThat(redirectUrl).startsWith("/mock-gateway/checkout/");

        Map<String, Object> row = payment(reference);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("account_id")).isEqualTo(accountId("ACC-T-0001"));
        assertThat(row.get("invoice_id")).isEqualTo(invoice.id);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("399.00");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(row.get("redirect_url")).isEqualTo(redirectUrl);
        assertThat(row.get("gateway_session_id")).isEqualTo(redirectUrl.substring("/mock-gateway/checkout/".length()));
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("initiated_by_user_id")).isEqualTo(userId(CUSTOMER_1));
        assertThat(row.get("completed_at")).isNull();

        // the gateway got the PAY reference as merchant ref, the amount from the invoice and the server-built returnUrl
        MockPaymentGateway.Session session = gateway.findSession((String) row.get("gateway_session_id")).orElseThrow();
        assertThat(session.paymentRef()).isEqualTo(reference);
        assertThat(session.amount()).isEqualByComparingTo("399.00");
        // erratum E4: returnUrl = {app.payment.return-url-base}/customer/payments/{reference}
        assertThat(session.returnUrl()).isEqualTo(returnUrlBase + "/customer/payments/" + reference);

        assertThat(auditCount("PAYMENT_INITIATED", reference, "SUCCESS")).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_INITIATED", reference))
                .contains("\"invoiceRef\": \"" + invoice.ref + "\"", "\"amount\": \"399.00\"");
        assertThat(jdbc.queryForObject("""
                SELECT actor_username FROM audit_events WHERE action = 'PAYMENT_INITIATED' AND entity_id = :ref
                """, Map.of("ref", reference), String.class)).isEqualTo(CUSTOMER_1);
    }

    @Test
    void partlyPaidInvoicePaysTheOutstandingAndSuspendedAccountMayPay() throws Exception {
        suspendAccount("ACC-T-0001", "OTHER");
        Invoice invoice = invoice("ACC-T-0001", "399.00", "200.00", "OPEN");

        String reference = reference(initiate(login(CUSTOMER_1), key(), request(invoice.id, "199.00"))
                .andExpect(status().isCreated()).andReturn());

        assertThat((BigDecimal) payment(reference).get("amount")).isEqualByComparingTo("199.00");
    }

    @Test
    void replayReturnsTheSameResponseAndKeyOnAnotherInvoiceIsAMismatch() throws Exception {
        Invoice first = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Invoice second = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);
        String key = key();

        Map<String, Object> created = json(initiate(session, key, request(first.id, "399.00"))
                .andExpect(status().isCreated()).andReturn());
        Map<String, Object> replay = json(initiate(session, key, request(first.id, "399.00"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(replay).isEqualTo(created);
        assertThat(paymentCount(first.id)).isEqualTo(1);
        assertProblem(initiate(session, key, request(second.id, "399.00")).andReturn(), 422, "idempotency-mismatch",
                PATH);
        assertThat(paymentCount(second.id)).isZero();
    }

    // ------------------------------------------------------------------ R12 rejections

    @Test
    void amountMismatchOrNothingToPayIs422() throws Exception {
        Cookie session = login(CUSTOMER_1);
        Invoice open = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Invoice paid = invoice("ACC-T-0001", "399.00", "399.00", "PAID");
        Invoice settledButOpen = invoice("ACC-T-0001", "399.00", "399.00", "OPEN");

        assertProblem(initiate(session, key(), request(open.id, "100.00")).andReturn(), 422, "business-rule", PATH);
        assertProblem(initiate(session, key(), request(open.id, "399.01")).andReturn(), 422, "business-rule", PATH);
        assertProblem(initiate(session, key(), request(paid.id, "399.00")).andReturn(), 422, "business-rule", PATH);
        assertProblem(initiate(session, key(), request(settledButOpen.id, "0.00")).andReturn(), 422, "business-rule",
                PATH);
        assertThat(paymentCount(open.id) + paymentCount(paid.id) + paymentCount(settledButOpen.id)).isZero();
    }

    @Test
    void anotherAccountsOrUnknownInvoiceIs404() throws Exception {
        Invoice other = invoice("ACC-T-0002", "599.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);

        assertProblem(initiate(session, key(), request(other.id, "599.00")).andReturn(), 404, "not-found", PATH);
        assertProblem(initiate(session, key(), request(999_999_999L, "599.00")).andReturn(), 404, "not-found", PATH);
        assertThat(paymentCount(other.id)).isZero();
    }

    @Test
    void openPendingPaymentIs409() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);
        initiate(session, key(), request(invoice.id, "399.00")).andExpect(status().isCreated());

        assertProblem(initiate(session, key(), request(invoice.id, "399.00")).andReturn(), 409, "conflict", PATH);
        assertThat(paymentCount(invoice.id)).isEqualTo(1);
    }

    @Test
    void reviewPaymentOnTheInvoiceIs409() throws Exception {
        // erratum E1: REVIEW is outside the one-open-payment key, initiate still refuses under the invoice lock
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        insertPayment(invoice, "REVIEW", null, null);

        assertProblem(initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andReturn(), 409, "conflict",
                PATH);
        assertThat(paymentCount(invoice.id)).isEqualTo(1);
    }

    @Test
    void parallelInitiatesOnOneInvoiceCreateOnePayment() throws Exception {
        for (int round = 0; round < 3; round++) {
            Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
            Cookie session = login(CUSTOMER_1);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Integer>> calls = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String key = key();
                    calls.add(pool.submit(() -> {
                        start.await();
                        return initiate(session, key, request(invoice.id, "399.00")).andReturn().getResponse()
                                .getStatus();
                    }));
                }
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> call : calls) {
                    statuses.add(call.get(30, TimeUnit.SECONDS));
                }
                assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 409);
            } finally {
                pool.shutdownNow();
            }
            assertThat(paymentCount(invoice.id)).isEqualTo(1);
        }
    }

    @Test
    void closedAccountOrInactiveCustomerIs422() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        assertProblem(initiate(login("+919900000003"), key(), request(invoice.id, "399.00")).andReturn(), 422,
                "business-rule", PATH);

        jdbc.update("UPDATE customers SET status = 'INACTIVE' WHERE customer_ref = 'CUST-T-0001'", Map.of());
        assertProblem(initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andReturn(), 422,
                "business-rule", PATH);
        assertThat(paymentCount(invoice.id)).isZero();
    }

    @Test
    void invalidRequestIs400() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);

        assertProblem(initiate(session, key(), request(invoice.id, "399")).andReturn(), 400, "validation", PATH);
        assertProblem(initiate(session, key(), request(invoice.id, "-399.00")).andReturn(), 400, "validation", PATH);
        Map<String, Object> noInvoice = request(invoice.id, "399.00");
        noInvoice.remove("invoiceId");
        assertProblem(initiate(session, key(), noInvoice).andReturn(), 400, "validation", PATH);
        assertProblem(mvc.perform(post(PATH).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(invoice.id, "399.00"))))
                .andReturn(), 400, "bad-request", PATH);
        assertThat(paymentCount(invoice.id)).isZero();
    }

    // ------------------------------------------------------------------ late-capture re-check

    @Test
    void recentExpiredPaymentStillPendingAtTheGatewayIs409() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        String earlier = insertPayment(invoice, "EXPIRED", 1, null);
        gateway.createCheckout(earlier, new BigDecimal("399.00"), "INR", "http://localhost/r");

        assertProblem(initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andReturn(), 409, "conflict",
                PATH);

        assertThat(payment(earlier).get("status")).isEqualTo("EXPIRED");
        assertThat(paymentCount(invoice.id)).isEqualTo(1);
    }

    @Test
    void recentFailedPaymentCapturedAtTheGatewayIsAppliedAnd409() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        String earlier = insertPayment(invoice, "FAILED", 2, null);
        String sessionId = gateway.createCheckout(earlier, new BigDecimal("399.00"), "INR", "http://localhost/r")
                .sessionId();
        jdbc.update("UPDATE payments SET gateway_session_id = :sid WHERE reference = :ref",
                Map.of("sid", sessionId, "ref", earlier));
        gateway.complete(sessionId, GatewayStatus.SUCCEEDED, Method.UPI);

        assertProblem(initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andReturn(), 409, "conflict",
                PATH);

        assertThat(payment(earlier).get("status")).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(invoice.id)).isEqualTo("PAID");
        assertThat(auditDetails("PAYMENT_LATE_CAPTURE", earlier)).contains("\"source\": \"initiate\"");
        assertThat(paymentCount(invoice.id)).isEqualTo(1);
    }

    @Test
    void recentFailedPaymentWithUnreachableGatewayIs503() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        String earlier = insertPayment(invoice, "FAILED", 3, null);
        gateway.setMode(Mode.UNAVAILABLE);

        assertProblem(initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andReturn(), 503,
                "dependency-unavailable", PATH);

        assertThat(payment(earlier).get("status")).isEqualTo("FAILED");
        assertThat(paymentCount(invoice.id)).isEqualTo(1);
    }

    @Test
    void failureOlderThanTheLateCaptureWindowIsNotRechecked() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        String old = insertPayment(invoice, "EXPIRED", 25, null);
        // a re-check would see a live checkout and answer 409
        gateway.createCheckout(old, new BigDecimal("399.00"), "INR", "http://localhost/r");

        initiate(login(CUSTOMER_1), key(), request(invoice.id, "399.00")).andExpect(status().isCreated());

        assertThat(paymentCount(invoice.id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ gateway failures (§3.1 tx2, §8)

    @Test
    void gatewayRefusedMarksFailedWith503AndTheSameKeyRetries() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);
        String key = key();
        gateway.setMode(Mode.UNAVAILABLE);

        assertProblem(initiate(session, key, request(invoice.id, "399.00")).andReturn(), 503,
                "dependency-unavailable", PATH);

        // tx1 committed the PENDING row before the call; tx2 moved it to FAILED
        List<Map<String, Object>> rows = payments(invoice.id);
        assertThat(rows).hasSize(1);
        String failed = (String) rows.get(0).get("reference");
        assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
        assertThat(rows.get(0).get("failure_reason")).isEqualTo("gateway_unavailable");
        assertThat(rows.get(0).get("gateway_session_id")).isNull();
        assertThat(auditCount("PAYMENT_INITIATED", failed, "FAILURE")).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_INITIATED", failed)).contains("\"reason\": \"gateway_unavailable\"");
        assertThat(auditDetails("PAYMENT_FAILED", failed)).contains("\"source\": \"initiate\"");

        gateway.setMode(Mode.NORMAL);
        String retried = reference(initiate(session, key, request(invoice.id, "399.00"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(retried).isNotEqualTo(failed);
        assertThat(payment(retried).get("status")).isEqualTo("PENDING");
        assertThat(payment(failed).get("status")).isEqualTo("FAILED");
    }

    @Test
    void gatewayTimeoutStaysPendingWith503AndIsReconciledByReference() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        Cookie session = login(CUSTOMER_1);
        gateway.setMode(Mode.TIMEOUT);

        assertProblem(initiate(session, key(), request(invoice.id, "399.00")).andReturn(), 503,
                "dependency-unavailable", PATH);

        List<Map<String, Object>> rows = payments(invoice.id);
        assertThat(rows).hasSize(1);
        String reference = (String) rows.get(0).get("reference");
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        assertThat(rows.get(0).get("failure_reason")).isEqualTo("gateway_timeout");
        assertThat(rows.get(0).get("gateway_session_id")).isNull();
        assertThat(auditCount("PAYMENT_INITIATED", reference, "FAILURE")).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_INITIATED", reference)).contains("\"reason\": \"gateway_timeout\"");

        // still open: a new attempt is refused until it is resolved
        gateway.setMode(Mode.NORMAL);
        assertProblem(initiate(session, key(), request(invoice.id, "399.00")).andReturn(), 409, "conflict", PATH);

        // the gateway did open a session for the reference; the customer pays; reconciliation polls by reference
        GatewayPaymentStatus pending = gateway.getStatus(reference);
        assertThat(pending.status()).isEqualTo(GatewayStatus.PENDING);
        gateway.complete(pending.sessionId(), GatewayStatus.SUCCEEDED, Method.CARD);
        GatewayPaymentStatus paid = gateway.getStatus(reference);
        Result result = transitions.succeeded(reference, new Capture(paid.sessionId(), paid.amount(), paid.currency(),
                paid.method(), paid.gatewayTxnId()), Source.RECONCILE);

        assertThat(result).isEqualTo(new Result(Result.Kind.APPLIED, "SUCCEEDED"));
        Map<String, Object> row = payment(reference);
        assertThat(row.get("gateway_session_id")).isEqualTo(pending.sessionId());
        assertThat(row.get("failure_reason")).isNull();
        assertThat(invoiceStatus(invoice.id)).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void initiateNeedsAuthenticationAndACustomer() throws Exception {
        Invoice invoice = invoice("ACC-T-0001", "399.00", "0.00", "OPEN");
        assertProblem(mvc.perform(post(PATH).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(invoice.id, "399.00"))))
                .andReturn(), 401, "unauthenticated", PATH);
        // INBOUND_AGENT, OUTBOUND_AGENT and BUSINESS_ANALYST lack payment.initiate; +919900000004 is unlinked
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(initiate(login(username), key(), request(invoice.id, "399.00")).andReturn(), 403,
                    "forbidden", PATH);
        }
        assertThat(paymentCount(invoice.id)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Invoice(long id, String ref) {}

    private Invoice invoice(String accountRef, String due, String paid, String status) {
        String ref = "INV-T-Z" + String.format("%05d", SEQ.incrementAndGet());
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                      amount_due, amount_paid, status, created_at, updated_at)
                VALUES (:accountId, :ref, CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
                        CURRENT_DATE + INTERVAL 15 DAY, :due, :paid, :status, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId(accountRef))
                .addValue("ref", ref)
                .addValue("due", new BigDecimal(due))
                .addValue("paid", new BigDecimal(paid))
                .addValue("status", status));
        return new Invoice(jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref", Map.of("ref", ref),
                Long.class), ref);
    }

    /** A payment of the invoice in {@code status}, completed {@code completedHoursAgo} hours ago (null = not set). */
    private String insertPayment(Invoice invoice, String status, Integer completedHoursAgo, String sessionId) {
        String ref = "PAY-TZ-" + String.format("%06d", SEQ.incrementAndGet());
        jdbc.update("""
                INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, gateway_session_id,
                                      initiated_by_user_id, initiated_at, completed_at, created_at, updated_at)
                SELECT :ref, i.account_id, i.id, 399.00, 'INR', :status, :sid, :userId,
                       UTC_TIMESTAMP(6) - INTERVAL 30 HOUR,
                       IF(:hours IS NULL, NULL, UTC_TIMESTAMP(6) - INTERVAL :hours HOUR),
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM invoices i WHERE i.id = :invoiceId
                """, new MapSqlParameterSource()
                .addValue("ref", ref)
                .addValue("status", status)
                .addValue("sid", sessionId)
                .addValue("userId", userId(CUSTOMER_1))
                .addValue("hours", completedHoursAgo)
                .addValue("invoiceId", invoice.id));
        return ref;
    }

    private static Map<String, Object> request(long invoiceId, String expectedAmount) {
        Map<String, Object> body = new HashMap<>();
        body.put("invoiceId", invoiceId);
        body.put("expectedAmount", expectedAmount);
        return body;
    }

    private static String key() {
        return "k-pay-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    private ResultActions initiate(Cookie session, String key, Map<String, Object> body) throws Exception {
        return mvc.perform(post(PATH).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String reference(MvcResult result) throws Exception {
        return (String) json(result).get("reference");
    }

    private Map<String, Object> payment(String reference) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE reference = :ref", Map.of("ref", reference));
    }

    private List<Map<String, Object>> payments(long invoiceId) {
        return jdbc.queryForList("SELECT * FROM payments WHERE invoice_id = :id ORDER BY id", Map.of("id", invoiceId));
    }

    private long paymentCount(long invoiceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE invoice_id = :id", Map.of("id", invoiceId),
                Long.class);
    }

    private String invoiceStatus(long invoiceId) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE id = :id", Map.of("id", invoiceId),
                String.class);
    }

    private long accountId(String accountRef) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_ref = :ref", Map.of("ref", accountRef),
                Long.class);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = :u", Map.of("u", username), Long.class);
    }

    private long auditCount(String action, String entityId, String outcome) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE action = :action AND entity_id = :id AND outcome = :outcome
                """, Map.of("action", action, "id", entityId, "outcome", outcome), Long.class);
    }

    private String auditDetails(String action, String entityId) {
        return jdbc.queryForObject("""
                SELECT CAST(details AS CHAR) FROM audit_events WHERE action = :action AND entity_id = :id
                ORDER BY id DESC LIMIT 1
                """, Map.of("action", action, "id", entityId), String.class);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.28." + (n / 250) + "." + (n % 250 + 1);
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
