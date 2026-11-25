package com.telecom.support.servicecase;

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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * E11 {@code GET} and E12 {@code POST /api/v1/customers/me/cases} end to end (design 02-customer backend R1, R9, R10,
 * R16, §4, §7, §9; CUS-05, CUS-06, BR-01/02/04/05/06/07). Test data: R__test_users_02_customer.sql (customer 1: cases
 * 900001 at now-10 d SLOW_DATA IN_PROGRESS, 900002 at now-2 d BILL_COPY CREATED, 900003 at now-120 d NO_SIGNAL CLOSED;
 * customer 2: 900004 at now-5 d; customer 3: CLOSED account). Reference data: V5 placeholder categories and SLAs.
 *
 * <p>Created cases cannot be removed ({@code case_activity} is append-only), so exact list counts use windows that end
 * yesterday, and the default-window test only looks at the test-data references.
 */
class CustomerCaseIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/cases";
    private static final String XSRF = "case-xsrf-token";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM sla_policies WHERE category_id IN (SELECT id FROM categories WHERE code LIKE 'T\\_%')",
                Map.of());
        jdbc.update("DELETE FROM categories WHERE code LIKE 'T\\_%' AND parent_id IS NOT NULL", Map.of());
        jdbc.update("DELETE FROM categories WHERE code LIKE 'T\\_%'", Map.of());
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE account_ref IN ('ACC-T-0001', 'ACC-T-0002')", Map.of());
    }

    // ------------------------------------------------------------------ E11

    @Test
    void defaultWindowListsOwnRecentCasesNewestFirstWithPublicStatus() throws Exception {
        MvcResult result = mvc.perform(get(PATH).cookie(login("+919900000001"))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> page = jsonObject(result);
        assertThat(page).containsOnlyKeys("items", "page", "size", "totalItems", "totalPages");
        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("size")).isEqualTo(20);
        List<Map<String, Object>> testDataItems = items(page).stream()
                .filter(i -> ((String) i.get("reference")).matches("SR-\\d{8}-9000\\d\\d"))
                .toList();
        // 900003 is older than 3 months; 900004 belongs to customer 2
        assertThat(testDataItems).extracting(i -> i.get("reference"))
                .containsExactly(reference("900002"), reference("900001"));
        assertThat(items(page)).allSatisfy(i -> assertThat(accountOf((String) i.get("reference")))
                .isEqualTo("ACC-T-0001"));

        Map<String, Object> billCopy = testDataItems.get(0);
        assertThat(billCopy).containsOnlyKeys("reference", "categoryName", "subcategoryName", "subject", "createdAt",
                "severity", "status");
        assertThat(billCopy.get("categoryName")).isEqualTo("Billing and payments");
        assertThat(billCopy.get("subcategoryName")).isEqualTo("Copy of a bill");
        assertThat(billCopy.get("subject")).isEqualTo("Need a copy of my bill");
        assertThat(billCopy.get("severity")).isEqualTo("LOW");
        assertThat(billCopy.get("status")).isEqualTo("SUBMITTED");
        assertThat((String) billCopy.get("createdAt")).endsWith("Z");
        assertThat(testDataItems.get(1).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void olderCasesOnlyWithAnExplicitWindow() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> page = jsonObject(mvc.perform(list(login("+919900000001"))
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString()))
                .andExpect(status().isOk()).andReturn());

        assertThat(items(page)).extracting(i -> i.get("reference"))
                .containsExactly(reference("900002"), reference("900001"), reference("900003"));
        assertThat(items(page).get(2).get("status")).isEqualTo("CLOSED");
        assertThat(items(page).get(2).get("categoryName")).isEqualTo("Network and coverage");
        assertThat(page.get("totalItems")).isEqualTo(3);
        assertThat(page.get("totalPages")).isEqualTo(1);
    }

    @Test
    void pagesAndSortsByCreatedAtOnly() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Cookie session = login("+919900000001");
        Map<String, Object> page = jsonObject(mvc.perform(list(session)
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString())
                        .param("page", "1").param("size", "2").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andReturn());

        // ascending: 900003, 900001 | 900002
        assertThat(items(page)).extracting(i -> i.get("reference")).containsExactly(reference("900002"));
        assertThat(page.get("page")).isEqualTo(1);
        assertThat(page.get("size")).isEqualTo(2);
        assertThat(page.get("totalItems")).isEqualTo(3);
        assertThat(page.get("totalPages")).isEqualTo(2);

        Map<String, Object> beyond = jsonObject(mvc.perform(list(session)
                        .param("from", today.minusDays(200).toString())
                        .param("to", today.minusDays(1).toString())
                        .param("page", "5"))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(beyond)).isEmpty();
        assertThat(beyond.get("totalItems")).isEqualTo(3);

        assertProblem(mvc.perform(list(session).param("sort", "subject,asc")).andReturn(), 400, "bad-request", PATH);
        assertProblem(mvc.perform(list(session).param("sort", "createdAt;DROP TABLE cases")).andReturn(), 400,
                "bad-request", PATH);
    }

    @Test
    void referenceSearchStaysInsideTheWindow() throws Exception {
        Cookie session = login("+919900000001");
        Map<String, Object> recent = jsonObject(mvc.perform(list(session).param("reference", reference("900001")))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(recent)).extracting(i -> i.get("reference")).containsExactly(reference("900001"));
        assertThat(recent.get("totalItems")).isEqualTo(1);

        // OQ 6: an exact reference does not bypass the default 3-month window
        Map<String, Object> old = jsonObject(mvc.perform(list(session).param("reference", reference("900003")))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(old)).isEmpty();
        Map<String, Object> oldInWindow = jsonObject(mvc.perform(list(session)
                        .param("reference", reference("900003"))
                        .param("from", LocalDate.now(IST).minusDays(200).toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(oldInWindow)).extracting(i -> i.get("reference")).containsExactly(reference("900003"));

        // another customer's reference is simply not found in the own list
        assertThat(items(jsonObject(mvc.perform(list(session).param("reference", reference("900004")))
                .andExpect(status().isOk()).andReturn()))).isEmpty();
    }

    @Test
    void listShowsOnlyTheOwnAccount() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> page = jsonObject(mvc.perform(list(login("+919900000002"))
                        .param("from", today.minusDays(366).toString())
                        .param("to", today.minusDays(1).toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(items(page)).extracting(i -> i.get("reference")).containsExactly(reference("900004"));
        assertThat(items(page).get(0).get("subcategoryName")).isEqualTo("Dispute a charge");
    }

    @Test
    void invalidWindowPagingOrReferenceIs400() throws Exception {
        Cookie session = login("+919900000001");
        LocalDate today = LocalDate.now(IST);
        assertProblem(mvc.perform(list(session).param("from", today.minusDays(367).toString())
                .param("to", today.toString())).andReturn(), 400, "bad-request", PATH);
        assertProblem(mvc.perform(list(session).param("from", today.toString())
                .param("to", today.minusDays(1).toString())).andReturn(), 400, "bad-request", PATH);
        assertProblem(mvc.perform(list(session).param("from", "2026-13-01")).andReturn(), 400, "bad-request", PATH);
        assertProblem(mvc.perform(list(session).param("size", "101")).andReturn(), 400, "validation", PATH);
        assertProblem(mvc.perform(list(session).param("page", "-1")).andReturn(), 400, "validation", PATH);
        assertProblem(mvc.perform(list(session).param("reference", "PAY-20261125-000001")).andReturn(), 400,
                "validation", PATH);
        // exactly 366 days is allowed
        mvc.perform(list(session).param("from", today.minusDays(366).toString()).param("to", today.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void listNeedsAuthenticationAndACustomer() throws Exception {
        assertProblem(mvc.perform(get(PATH)).andReturn(), 401, "unauthenticated", PATH);
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(mvc.perform(get(PATH).cookie(login(username))).andReturn(), 403, "forbidden", PATH);
        }
    }

    // ------------------------------------------------------------------ E12

    @Test
    void createsWithLeafStoredParentSeverityAndParentSla() throws Exception {
        // PAYMENT_MISSING: no own severity → BILLING's MEDIUM; no (leaf, MEDIUM) SLA → (BILLING, MEDIUM) 240/2880
        long auditsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'CASE_CREATED'", Map.of(), Long.class);
        Map<String, Object> request = request("BILLING", "PAYMENT_MISSING", "SMS");
        request.put("accountId", accountId("ACC-T-0002"));
        request.put("channel", "INBOUND_AGENT");
        request.put("contactMsisdn", "+919900000002");

        MvcResult result = create(login("+919900000001"), "k-create", request)
                .andExpect(status().isCreated()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(result.getResponse().getHeader("Location")).isNull();
        Map<String, Object> body = jsonObject(result);
        assertThat(body).containsOnlyKeys("reference", "caseType", "status", "severity", "createdAt", "contactMsisdn",
                "accountRef");
        String reference = (String) body.get("reference");
        assertThat(reference).matches("SR-" + LocalDate.now(IST).toString().replace("-", "") + "-\\d{6,}");
        assertThat(body.get("caseType")).isEqualTo("COMPLAINT");
        assertThat(body.get("status")).isEqualTo("SUBMITTED");
        assertThat(body.get("severity")).isEqualTo("MEDIUM");
        assertThat(body.get("contactMsisdn")).isEqualTo("+919900000001");
        assertThat(body.get("accountRef")).isEqualTo("ACC-T-0001");
        assertThat((String) body.get("createdAt")).endsWith("Z");

        Map<String, Object> row = caseRow(reference);
        assertThat(row.get("account_ref")).isEqualTo("ACC-T-0001");
        assertThat(row.get("category_code")).isEqualTo("PAYMENT_MISSING");
        assertThat(row.get("case_type")).isEqualTo("COMPLAINT");
        assertThat(row.get("channel")).isEqualTo("CUSTOMER_PORTAL");
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("severity")).isEqualTo("MEDIUM");
        assertThat(row.get("preferred_contact")).isEqualTo("SMS");
        assertThat(row.get("contact_msisdn")).isEqualTo("+919900000001");
        assertThat(row.get("subscription_id")).isNull();
        assertThat(row.get("creator")).isEqualTo("+919900000001");
        assertThat(row.get("subject")).isEqualTo("Payment not showing up");
        assertDueTimes(row, 240, 2880);

        List<Map<String, Object>> activity = jdbc.queryForList("""
                SELECT a.activity_type, a.visibility, a.new_value, u.username
                FROM case_activity a JOIN cases c ON c.id = a.case_id JOIN users u ON u.id = a.actor_user_id
                WHERE c.reference = :ref
                """, Map.of("ref", reference));
        assertThat(activity).containsExactly(Map.of("activity_type", "CREATED", "visibility", "PUBLIC",
                "new_value", "CREATED", "username", "+919900000001"));

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, entity_type, outcome, details FROM audit_events
                WHERE action = 'CASE_CREATED' AND entity_id = :ref
                """, Map.of("ref", reference));
        assertThat(audit.get("actor_username")).isEqualTo("+919900000001");
        assertThat(audit.get("entity_type")).isEqualTo("CASE");
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        assertThat(objectMapper.readValue((String) audit.get("details"), new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("category", "PAYMENT_MISSING", "caseType", "COMPLAINT", "severity", "MEDIUM",
                        "channel", "CUSTOMER_PORTAL"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'CASE_CREATED'", Map.of(),
                Long.class)).isEqualTo(auditsBefore + 1);
    }

    @Test
    void leafSeverityAndLeafSlaWinOverTheParent() throws Exception {
        // NO_SIGNAL: own CRITICAL and its own (leaf, CRITICAL) SLA 30/240; NETWORK would be HIGH 60/1440
        Map<String, Object> body = jsonObject(create(login("+919900000001"), "k-leaf",
                request("NETWORK", "NO_SIGNAL", "PHONE")).andExpect(status().isCreated()).andReturn());
        assertThat(body.get("severity")).isEqualTo("CRITICAL");
        assertThat(body.get("caseType")).isEqualTo("COMPLAINT");
        assertDueTimes(caseRow((String) body.get("reference")), 30, 240);
    }

    @Test
    void topLevelWithoutChildrenIsTheLeaf() throws Exception {
        Cookie session = login("+919900000001");
        Map<String, Object> body = jsonObject(create(session, "k-top", request("OTHER", null, "PHONE"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(body.get("severity")).isEqualTo("LOW");
        assertThat(body.get("caseType")).isEqualTo("REQUEST");
        Map<String, Object> row = caseRow((String) body.get("reference"));
        assertThat(row.get("category_code")).isEqualTo("OTHER");
        assertDueTimes(row, 480, 4320);

        assertProblem(create(session, "k-top-sub", request("OTHER", "BILL_COPY", "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-no-sub", request("BILLING", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-wrong-sub", request("BILLING", "NO_SIGNAL", "PHONE")).andReturn(), 422,
                "business-rule", PATH);
    }

    @Test
    void missingSlaRowIs422AndCreatesNothing() throws Exception {
        insertCategory("T_NO_SLA", null, "CUSTOMER", true, "HIGH");
        insertCategory("T_NO_SLA_LEAF", "T_NO_SLA", "CUSTOMER", true, null);
        long before = caseCount();

        assertProblem(create(login("+919900000001"), "k-nosla", request("T_NO_SLA", "T_NO_SLA_LEAF", "PHONE"))
                .andReturn(), 422, "business-rule", PATH);
        assertThat(caseCount()).isEqualTo(before);

        // an SLA row for another severity, or an inactive one, does not count
        jdbc.update("""
                INSERT INTO sla_policies (category_id, severity, response_minutes, resolution_minutes, active,
                                          created_at, updated_at)
                SELECT id, sev.severity, 45, 900, sev.active, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM categories
                JOIN (SELECT 'LOW' AS severity, TRUE AS active UNION ALL SELECT 'HIGH', FALSE) sev
                WHERE code IN ('T_NO_SLA', 'T_NO_SLA_LEAF')
                """, Map.of());
        assertProblem(create(login("+919900000001"), "k-nosla-2", request("T_NO_SLA", "T_NO_SLA_LEAF", "PHONE"))
                .andReturn(), 422, "business-rule", PATH);
        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void internalInactiveOrUnknownCategoryIs422() throws Exception {
        insertCategory("T_INTERNAL", null, "INTERNAL", true, "LOW");
        insertCategory("T_INACTIVE", null, "CUSTOMER", false, "LOW");
        insertCategory("T_BILL_INTERNAL", "BILLING", "INTERNAL", true, "LOW");
        insertCategory("T_BILL_OLD", "BILLING", "CUSTOMER", false, "LOW");
        Cookie session = login("+919900000001");
        long before = caseCount();

        assertProblem(create(session, "k-int", request("T_INTERNAL", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-inact", request("T_INACTIVE", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-sub-int", request("BILLING", "T_BILL_INTERNAL", "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-sub-old", request("BILLING", "T_BILL_OLD", "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertProblem(create(session, "k-unknown", request("NO_SUCH", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        // a subcategory code used as the top level
        assertProblem(create(session, "k-leaf-top", request("BILL_COPY", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);
        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void emailContactNeedsAVerifiedEmail() throws Exception {
        // customer 2 has no email
        assertProblem(create(login("+919900000002"), "k-email", request("OTHER", null, "EMAIL")).andReturn(), 422,
                "business-rule", PATH);
        // customer 1's email is verified
        Map<String, Object> body = jsonObject(create(login("+919900000001"), "k-email-ok",
                request("OTHER", null, "EMAIL")).andExpect(status().isCreated()).andReturn());
        assertThat(caseRow((String) body.get("reference")).get("preferred_contact")).isEqualTo("EMAIL");
    }

    @Test
    void subscriptionMustBeOwn() throws Exception {
        Cookie session = login("+919900000001");
        long own = subscriptionId("+919900000011");
        Map<String, Object> request = request("PLAN_SERVICES", "ROAMING", "SMS");
        request.put("subscriptionId", own);
        Map<String, Object> body = jsonObject(create(session, "k-sub-own", request)
                .andExpect(status().isCreated()).andReturn());
        assertThat(((Number) caseRow((String) body.get("reference")).get("subscription_id")).longValue())
                .isEqualTo(own);

        long before = caseCount();
        request.put("subscriptionId", subscriptionId("+919900000002"));
        assertProblem(create(session, "k-sub-other", request).andReturn(), 404, "not-found", PATH);
        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void closedAccountIs422ButSuspendedMayCreate() throws Exception {
        assertProblem(create(login("+919900000003"), "k-closed", request("OTHER", null, "PHONE")).andReturn(), 422,
                "business-rule", PATH);

        jdbc.update("UPDATE accounts SET status = 'SUSPENDED' WHERE account_ref = 'ACC-T-0002'", Map.of());
        create(login("+919900000002"), "k-susp", request("OTHER", null, "PHONE")).andExpect(status().isCreated());
    }

    @Test
    void sameKeyReplaysOneCaseAndAnotherBodyIsAMismatch() throws Exception {
        Cookie session = login("+919900000001");
        long before = caseCount();
        Map<String, Object> request = request("SIM_DEVICE", "SIM_REPLACE", "SMS");

        Map<String, Object> first = jsonObject(create(session, "k-replay", request)
                .andExpect(status().isCreated()).andReturn());
        Map<String, Object> second = jsonObject(create(session, "k-replay", request)
                .andExpect(status().isCreated()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(caseCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'CASE_CREATED'"
                + " AND entity_id = :ref", Map.of("ref", first.get("reference")), Long.class)).isEqualTo(1);

        request.put("subject", "A different subject line");
        assertProblem(create(session, "k-replay", request).andReturn(), 422, "idempotency-mismatch", PATH);
        assertThat(caseCount()).isEqualTo(before + 1);
    }

    @Test
    void missingKeyOrInvalidBodyIs400() throws Exception {
        Cookie session = login("+919900000001");
        assertProblem(mvc.perform(post(PATH).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("OTHER", null, "PHONE"))))
                .andReturn(), 400, "bad-request", PATH);

        Map<String, Object> shortSubject = request("OTHER", null, "PHONE");
        shortSubject.put("subject", "Hi");
        assertProblem(create(session, "k-short", shortSubject).andReturn(), 400, "validation", PATH);
        Map<String, Object> longDescription = request("OTHER", null, "PHONE");
        longDescription.put("description", "x".repeat(4001));
        assertProblem(create(session, "k-long", longDescription).andReturn(), 400, "validation", PATH);
        Map<String, Object> noCategory = request("OTHER", null, "PHONE");
        noCategory.remove("categoryCode");
        assertProblem(create(session, "k-nocat", noCategory).andReturn(), 400, "validation", PATH);
        assertProblem(create(session, "k-contact", request("OTHER", null, "POST")).andReturn(), 400, "bad-request",
                PATH);
    }

    @Test
    void createNeedsAuthenticationAndACustomer() throws Exception {
        assertProblem(mvc.perform(post(PATH).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .header("Idempotency-Key", "k-anon").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("OTHER", null, "PHONE"))))
                .andReturn(), 401, "unauthenticated", PATH);
        // INBOUND_AGENT holds case.create.onbehalf only; the others lack case.create; +919900000004 is unlinked
        long before = caseCount();
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(create(login(username), "k-403-" + username, request("OTHER", null, "PHONE")).andReturn(),
                    403, "forbidden", PATH);
        }
        assertThat(caseCount()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private void assertDueTimes(Map<String, Object> row, long responseMinutes, long resolutionMinutes) {
        LocalDateTime created = (LocalDateTime) row.get("created_at");
        assertThat(ChronoUnit.MINUTES.between(created, (LocalDateTime) row.get("response_due_at")))
                .isEqualTo(responseMinutes);
        assertThat(ChronoUnit.MINUTES.between(created, (LocalDateTime) row.get("sla_due_at")))
                .isEqualTo(resolutionMinutes);
    }

    private Map<String, Object> caseRow(String reference) {
        return jdbc.queryForMap("""
                SELECT a.account_ref, cat.code AS category_code, c.case_type, c.channel, c.status, c.severity,
                       c.preferred_contact, c.contact_msisdn, c.subscription_id, u.username AS creator, c.subject,
                       c.created_at, c.response_due_at, c.sla_due_at
                FROM cases c
                JOIN accounts a ON a.id = c.account_id
                JOIN categories cat ON cat.id = c.category_id
                JOIN users u ON u.id = c.created_by_user_id
                WHERE c.reference = :ref
                """, Map.of("ref", reference));
    }

    private static Map<String, Object> request(String category, String subcategory, String contact) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("categoryCode", category);
        request.put("subcategoryCode", subcategory);
        request.put("subject", "Payment not showing up");
        request.put("description", "I paid my bill yesterday but it still shows as due.");
        request.put("preferredContact", contact);
        return request;
    }

    private void insertCategory(String code, String parentCode, String audience, boolean active, String severity) {
        jdbc.update("""
                INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active,
                                        sort_order, created_at, updated_at)
                VALUES (:code, :code, (SELECT id FROM (SELECT id FROM categories WHERE code = :parent) p), :audience,
                        'REQUEST', :severity, :active, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("parent", parentCode)
                .addValue("audience", audience)
                .addValue("active", active)
                .addValue("severity", severity));
    }

    private String reference(String seq) {
        return jdbc.queryForObject("SELECT reference FROM cases WHERE reference LIKE :pattern",
                Map.of("pattern", "SR-%-" + seq), String.class);
    }

    private String accountOf(String reference) {
        return jdbc.queryForObject("""
                SELECT a.account_ref FROM cases c JOIN accounts a ON a.id = c.account_id WHERE c.reference = :ref
                """, Map.of("ref", reference), String.class);
    }

    private long accountId(String accountRef) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_ref = :ref", Map.of("ref", accountRef),
                Long.class);
    }

    private long subscriptionId(String msisdn) {
        return jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = :msisdn AND status <> 'TERMINATED'",
                Map.of("msisdn", msisdn), Long.class);
    }

    private long caseCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cases", Map.of(), Long.class);
    }

    private MockHttpServletRequestBuilder list(Cookie session) {
        return get(PATH).cookie(session);
    }

    private ResultActions create(Cookie session, String key, Map<String, Object> request) throws Exception {
        return mvc.perform(post(PATH).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HashMap<>(request))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> jsonObject(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.26." + (n / 250) + "." + (n % 250 + 1);
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
