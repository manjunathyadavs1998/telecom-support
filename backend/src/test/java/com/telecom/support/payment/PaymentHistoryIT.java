package com.telecom.support.payment;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * E13 invoices, E14 payment history, E16 one payment and E17 receipt end to end (design 02-customer backend R1, R11, R15,
 * R16, §4, §9; CUS-07.1–07.4, CUS-08.1, CUS-08.3–08.5). Test data: R__test_users_02_customer.sql (customer 1: invoices
 * INV-T-0001 PAID at today-120 d, INV-T-0002 PAID at today-30 d, INV-T-0003 OPEN today; payments 900001 SUCCEEDED at
 * now-118 d, 900002 FAILED (CARD, declined) and 900003 SUCCEEDED (UPI) at now-28/27 d; customer 2: INV-T-0101/0102 and
 * payment 900004; customer 3: CLOSED account, nothing billed).
 *
 * <p>Later tasks create payments "now", so exact list counts use windows that end yesterday and the default-window test
 * only looks at the test-data references.
 */
class PaymentHistoryIT extends AbstractMySqlIT {

    private static final String INVOICES = "/api/v1/customers/me/invoices";
    private static final String PAYMENTS = "/api/v1/customers/me/payments";
    private static final String XSRF = "payment-xsrf-token";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM invoices WHERE invoice_ref LIKE 'INV-T-X%'", Map.of());
    }

    // ------------------------------------------------------------------ E13

    @Test
    void invoicesNewestFirstWithOutstandingAndOnlyOwn() throws Exception {
        MvcResult result = mvc.perform(get(INVOICES).cookie(login("+919900000001")))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        List<Map<String, Object>> invoices = jsonList(result);
        assertThat(invoices).extracting(i -> i.get("invoiceRef"))
                .containsExactly("INV-T-0003", "INV-T-0002", "INV-T-0001");

        Map<String, Object> paid = invoices.get(1);
        assertThat(paid).containsOnlyKeys("id", "invoiceRef", "periodStart", "periodEnd", "issueDate", "dueDate",
                "amountDue", "amountPaid", "outstanding", "status");
        LocalDate today = LocalDate.now(IST);
        assertThat(paid.get("id")).isEqualTo((int) invoiceId("INV-T-0002"));
        assertThat(paid.get("amountDue")).isEqualTo("399.00");
        assertThat(paid.get("amountPaid")).isEqualTo("399.00");
        assertThat(paid.get("outstanding")).isEqualTo("0.00");
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat(LocalDate.parse((String) paid.get("dueDate")))
                .isEqualTo(LocalDate.parse((String) paid.get("issueDate")).plusDays(15));
        assertThat(LocalDate.parse((String) paid.get("issueDate"))).isBetween(today.minusDays(31), today.minusDays(29));

        Map<String, Object> open = invoices.get(0);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT amount_due, amount_paid, status FROM invoices WHERE invoice_ref = 'INV-T-0003'", Map.of());
        BigDecimal outstanding = ((BigDecimal) row.get("amount_due")).subtract((BigDecimal) row.get("amount_paid"));
        assertThat(open.get("outstanding")).isEqualTo(outstanding.setScale(2).toPlainString());
        assertThat(open.get("status")).isEqualTo(row.get("status"));
    }

    @Test
    void invoicesAreTheLatestTwelveAndFilterByStatus() throws Exception {
        long accountId = jdbc.queryForObject("SELECT id FROM accounts WHERE account_ref = 'ACC-T-0002'", Map.of(),
                Long.class);
        for (int i = 1; i <= 13; i++) {
            jdbc.update("""
                    INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                          amount_due, amount_paid, status, created_at, updated_at)
                    VALUES (:accountId, :ref, CURRENT_DATE - INTERVAL (60 + :i) DAY, CURRENT_DATE - INTERVAL (31 + :i) DAY,
                            CURRENT_DATE - INTERVAL (30 + :i) DAY, CURRENT_DATE - INTERVAL (15 + :i) DAY,
                            100.00, 40.00, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, new MapSqlParameterSource()
                    .addValue("accountId", accountId)
                    .addValue("ref", "INV-T-X%02d".formatted(i))
                    .addValue("i", i));
        }
        Cookie session = login("+919900000002");

        List<Map<String, Object>> all = jsonList(mvc.perform(get(INVOICES).cookie(session))
                .andExpect(status().isOk()).andReturn());
        // INV-T-0102 today, INV-T-0101 today-30 d, then X01 (today-31 d) .. X10
        assertThat(all).hasSize(12);
        assertThat(all).extracting(i -> i.get("invoiceRef")).startsWith("INV-T-0102", "INV-T-0101", "INV-T-X01")
                .endsWith("INV-T-X10");
        assertThat(all.get(2).get("outstanding")).isEqualTo("60.00");

        List<Map<String, Object>> paid = jsonList(mvc.perform(get(INVOICES).cookie(session).param("status", "PAID"))
                .andExpect(status().isOk()).andReturn());
        assertThat(paid).extracting(i -> i.get("invoiceRef")).containsExactly("INV-T-0101");

        List<Map<String, Object>> open = jsonList(mvc.perform(get(INVOICES).cookie(session).param("status", "OPEN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(open).hasSize(12).allSatisfy(i -> assertThat(i.get("status")).isEqualTo("OPEN"));
        assertThat(open).extracting(i -> i.get("invoiceRef")).doesNotContain("INV-T-X12", "INV-T-X13");

        assertProblem(mvc.perform(get(INVOICES).cookie(session).param("status", "DRAFT")).andReturn(), 400,
                "bad-request", INVOICES);
    }

    @Test
    void closedAccountMayStillRead() throws Exception {
        Cookie session = login("+919900000003");
        assertThat(jsonList(mvc.perform(get(INVOICES).cookie(session)).andExpect(status().isOk()).andReturn()))
                .isEmpty();
        Map<String, Object> page = jsonObject(mvc.perform(get(PAYMENTS).cookie(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.get("totalItems")).isEqualTo(0);
        assertThat(page.get("totalPages")).isEqualTo(0);
    }

    // ------------------------------------------------------------------ E14

    @Test
    void defaultWindowListsOwnRecentPaymentsNewestFirst() throws Exception {
        MvcResult result = mvc.perform(get(PAYMENTS).cookie(login("+919900000001")))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> page = jsonObject(result);
        assertThat(page).containsOnlyKeys("items", "page", "size", "totalItems", "totalPages");
        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("size")).isEqualTo(20);
        List<Map<String, Object>> testData = items(page).stream()
                .filter(i -> ((String) i.get("reference")).matches("PAY-\\d{8}-9000\\d\\d"))
                .toList();
        // 900001 is older than 3 months; 900004 belongs to customer 2
        assertThat(testData).extracting(i -> i.get("reference")).containsExactly(ref("900003"), ref("900002"));
        assertThat(items(page)).allSatisfy(i -> assertThat(accountOf((String) i.get("reference")))
                .isEqualTo("ACC-T-0001"));

        Map<String, Object> succeeded = testData.get(0);
        assertThat(succeeded).containsOnlyKeys("reference", "invoiceRef", "amount", "currency", "method", "status",
                "failureReason", "initiatedAt", "completedAt", "receiptAvailable");
        assertThat(succeeded.get("invoiceRef")).isEqualTo("INV-T-0002");
        assertThat(succeeded.get("amount")).isEqualTo("399.00");
        assertThat(succeeded.get("currency")).isEqualTo("INR");
        assertThat(succeeded.get("method")).isEqualTo("UPI");
        assertThat(succeeded.get("status")).isEqualTo("SUCCEEDED");
        assertThat(succeeded.get("failureReason")).isNull();
        assertThat((String) succeeded.get("initiatedAt")).endsWith("Z");
        assertThat((String) succeeded.get("completedAt")).endsWith("Z");
        assertThat(succeeded.get("receiptAvailable")).isEqualTo(true);

        Map<String, Object> failed = testData.get(1);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("method")).isEqualTo("CARD");
        assertThat(failed.get("failureReason")).isEqualTo("declined");
        assertThat(failed.get("receiptAvailable")).isEqualTo(false);
    }

    @Test
    void olderPaymentsOnlyWithAnExplicitWindow() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> page = jsonObject(mvc.perform(get(PAYMENTS).cookie(login("+919900000001"))
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString()))
                .andExpect(status().isOk()).andReturn());

        assertThat(items(page)).extracting(i -> i.get("reference"))
                .containsExactly(ref("900003"), ref("900002"), ref("900001"));
        assertThat(items(page).get(2).get("invoiceRef")).isEqualTo("INV-T-0001");
        assertThat(page.get("totalItems")).isEqualTo(3);
        assertThat(page.get("totalPages")).isEqualTo(1);
    }

    @Test
    void onlyToGivenListsTheThreeMonthsBeforeIt() throws Exception {
        // review C11: a missing from is anchored to the given to, so an old to is not a 400 the user did not cause
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> page = jsonObject(mvc.perform(get(PAYMENTS).cookie(login("+919900000001"))
                        .param("to", today.minusDays(100).toString()))
                .andExpect(status().isOk()).andReturn());

        assertThat(items(page)).extracting(i -> i.get("reference")).containsExactly(ref("900001"));
    }

    @Test
    void pagesAndSortsByInitiatedAtOnly() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Cookie session = login("+919900000001");
        Map<String, Object> page = jsonObject(mvc.perform(get(PAYMENTS).cookie(session)
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString())
                        .param("page", "1").param("size", "2").param("sort", "initiatedAt,asc"))
                .andExpect(status().isOk()).andReturn());

        // ascending: 900001, 900002 | 900003
        assertThat(items(page)).extracting(i -> i.get("reference")).containsExactly(ref("900003"));
        assertThat(page.get("page")).isEqualTo(1);
        assertThat(page.get("size")).isEqualTo(2);
        assertThat(page.get("totalItems")).isEqualTo(3);
        assertThat(page.get("totalPages")).isEqualTo(2);

        Map<String, Object> beyond = jsonObject(mvc.perform(get(PAYMENTS).cookie(session)
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString())
                        .param("page", "5"))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(beyond)).isEmpty();
        assertThat(beyond.get("totalItems")).isEqualTo(3);

        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("sort", "amount,desc")).andReturn(), 400,
                "bad-request", PAYMENTS);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("sort", "initiatedAt;DROP TABLE payments"))
                .andReturn(), 400, "bad-request", PAYMENTS);
    }

    @Test
    void invalidWindowOrPagingIs400() throws Exception {
        Cookie session = login("+919900000001");
        LocalDate today = LocalDate.now(IST);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("from", today.minusDays(367).toString())
                .param("to", today.toString())).andReturn(), 400, "bad-request", PAYMENTS);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("from", today.toString())
                .param("to", today.minusDays(1).toString())).andReturn(), 400, "bad-request", PAYMENTS);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("to", "2026-02-30")).andReturn(), 400,
                "bad-request", PAYMENTS);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("size", "101")).andReturn(), 400,
                "validation", PAYMENTS);
        assertProblem(mvc.perform(get(PAYMENTS).cookie(session).param("page", "-1")).andReturn(), 400,
                "validation", PAYMENTS);
        // exactly 366 days is allowed
        mvc.perform(get(PAYMENTS).cookie(session).param("from", today.minusDays(366).toString())
                .param("to", today.toString())).andExpect(status().isOk());
    }

    @Test
    void listShowsOnlyTheOwnAccount() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> page = jsonObject(mvc.perform(get(PAYMENTS).cookie(login("+919900000002"))
                        .param("from", today.minusDays(366).toString())
                        .param("to", today.minusDays(1).toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(page)).extracting(i -> i.get("reference")).containsExactly(ref("900004"));
        assertThat(items(page).get(0).get("method")).isEqualTo("NETBANKING");
    }

    // ------------------------------------------------------------------ E16, E17

    @Test
    void getOwnPaymentByReference() throws Exception {
        Cookie session = login("+919900000001");
        String reference = ref("900002");

        Map<String, Object> payment = jsonObject(mvc.perform(get(PAYMENTS + "/" + reference).cookie(session))
                .andExpect(status().isOk()).andReturn());

        assertThat(payment).containsOnlyKeys("reference", "invoiceRef", "amount", "currency", "method", "status",
                "failureReason", "initiatedAt", "completedAt", "receiptAvailable");
        assertThat(payment.get("reference")).isEqualTo(reference);
        assertThat(payment.get("invoiceRef")).isEqualTo("INV-T-0002");
        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(payment.get("receiptAvailable")).isEqualTo(false);
        // outside the list window, a reference is still readable
        assertThat(jsonObject(mvc.perform(get(PAYMENTS + "/" + ref("900001")).cookie(session))
                .andExpect(status().isOk()).andReturn()).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void anotherAccountsOrUnknownPaymentIs404() throws Exception {
        Cookie session = login("+919900000001");
        String other = ref("900004");
        assertProblem(mvc.perform(get(PAYMENTS + "/" + other).cookie(session)).andReturn(), 404, "not-found",
                PAYMENTS + "/" + other);
        assertProblem(mvc.perform(get(PAYMENTS + "/" + other + "/receipt").cookie(session)).andReturn(), 404,
                "not-found", PAYMENTS + "/" + other + "/receipt");
        assertProblem(mvc.perform(get(PAYMENTS + "/PAY-20000101-999999").cookie(session)).andReturn(), 404,
                "not-found", PAYMENTS + "/PAY-20000101-999999");
        assertProblem(mvc.perform(get(PAYMENTS + "/not-a-reference").cookie(session)).andReturn(), 404,
                "not-found", PAYMENTS + "/not-a-reference");
    }

    @Test
    void receiptOnlyForSucceeded() throws Exception {
        Cookie session = login("+919900000001");
        String reference = ref("900003");

        MvcResult result = mvc.perform(get(PAYMENTS + "/" + reference + "/receipt").cookie(session))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> receipt = jsonObject(result);
        assertThat(receipt).containsOnlyKeys("paymentReference", "invoiceRef", "accountRef", "customerName", "amount",
                "currency", "method", "gatewayTransactionId", "paidAt");
        assertThat(receipt.get("paymentReference")).isEqualTo(reference);
        assertThat(receipt.get("invoiceRef")).isEqualTo("INV-T-0002");
        assertThat(receipt.get("accountRef")).isEqualTo("ACC-T-0001");
        assertThat(receipt.get("customerName")).isEqualTo("Test Customer");
        assertThat(receipt.get("amount")).isEqualTo("399.00");
        assertThat(receipt.get("currency")).isEqualTo("INR");
        assertThat(receipt.get("method")).isEqualTo("UPI");
        assertThat(receipt.get("gatewayTransactionId")).isEqualTo("test-txn-0003");
        assertThat((String) receipt.get("paidAt")).endsWith("Z");

        String failed = ref("900002");
        assertProblem(mvc.perform(get(PAYMENTS + "/" + failed + "/receipt").cookie(session)).andReturn(), 422,
                "business-rule", PAYMENTS + "/" + failed + "/receipt");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void everyEndpointNeedsAuthenticationAndACustomer() throws Exception {
        String own = ref("900003");
        List<String> paths = List.of(INVOICES, PAYMENTS, PAYMENTS + "/" + own, PAYMENTS + "/" + own + "/receipt");
        for (String path : paths) {
            assertProblem(mvc.perform(get(path)).andReturn(), 401, "unauthenticated", path);
        }
        // INBOUND_AGENT holds payment.read but is not a customer; T-OUT/T-BUS lack it; +919900000004 is unlinked
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            Cookie session = login(username);
            for (String path : paths) {
                assertProblem(mvc.perform(get(path).cookie(session)).andReturn(), 403, "forbidden", path);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ref(String seq) {
        return jdbc.queryForObject("SELECT reference FROM payments WHERE reference LIKE :pattern",
                Map.of("pattern", "PAY-%-" + seq), String.class);
    }

    private String accountOf(String reference) {
        return jdbc.queryForObject("""
                SELECT a.account_ref FROM payments p JOIN accounts a ON a.id = p.account_id WHERE p.reference = :ref
                """, Map.of("ref", reference), String.class);
    }

    private long invoiceId(String invoiceRef) {
        return jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref", Map.of("ref", invoiceRef),
                Long.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> jsonObject(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> jsonList(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.27." + (n / 250) + "." + (n % 250 + 1);
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
