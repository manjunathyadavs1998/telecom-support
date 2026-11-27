package com.telecom.support.servicecase;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.customer.InboundTestClock;
import com.telecom.support.customer.VerifiedProbeController;
import com.telecom.support.identity.AppUserDetailsService;
import com.telecom.support.identity.Permissions;
import com.telecom.support.identity.TestPrincipals;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Categories for agents I11 and create on behalf I12 (design 03-inbound backend R5, R11, R12, §4, §7, §9; INB-03,
 * BR-01/02/04/07, §14 #4, NFR-REL). Every seeded role holding {@code case.create.onbehalf} also holds
 * {@code case.category.internal} and {@code case.severity.override}, so their absence is proven with a
 * reduced-authority principal ({@link TestPrincipals}). Runs at the shared inbound clock's T0; VERIFIED checks are
 * inserted directly. Customers: CUST-T-0006 (SUSPENDED account, verified email, primary +919900000006, second
 * +919900000016), CUST-T-0007 (no verified email), CUST-T-0008 (INACTIVE), CUST-T-0003 (CLOSED, TERMINATED primary),
 * CUST-T-0009 (made primary-less for one test), CUST-T-0001 (another account). Created cases stay (append-only
 * activity); other ITs only assert their own references.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class AgentCaseCreateIT extends AbstractMySqlIT {

    private static final String XSRF = "case-create-xsrf-token";
    private static final String CATEGORIES = "/api/v1/agent/case-categories";
    private static final Instant T0 = InboundTestClock.T0;
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AppUserDetailsService userDetails;

    private long c1;
    private long c3;
    private long c6;
    private long c7;
    private long c8;
    private long c9;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(T0);
        cleanTables();
        c1 = customerId("CUST-T-0001");
        c3 = customerId("CUST-T-0003");
        c6 = customerId("CUST-T-0006");
        c7 = customerId("CUST-T-0007");
        c8 = customerId("CUST-T-0008");
        c9 = customerId("CUST-T-0009");
    }

    @AfterEach
    void cleanUp() {
        cleanTables();
        jdbc.update("""
                UPDATE subscriptions s JOIN accounts a ON a.id = s.account_id
                SET s.is_primary = TRUE WHERE a.account_ref = 'ACC-T-0009' AND s.msisdn = '+919900000009'
                """, Map.of());
        jdbc.update("DELETE FROM categories WHERE code = 'T_AGENT_NOSLA'", Map.of());
        InboundTestClock.CLOCK.set(T0);
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM identity_checks WHERE created_at >= :t", Map.of("t", utc(T0.minus(Duration.ofDays(2)))));
        jdbc.update("DELETE FROM idempotency_keys WHERE operation = 'CASE_CREATE_ONBEHALF'", Map.of());
    }

    // ------------------------------------------------------------------ I11

    @Test
    void categoriesIncludeInternalOnesForHoldersOfTheirPermission() throws Exception {
        List<Map<String, Object>> all = list(mvc.perform(get(CATEGORIES).cookie(login("T-IN")))
                .andExpect(status().isOk()).andReturn());

        assertThat(all).extracting(c -> c.get("code")).contains("BILLING", "NETWORK", "OTHER", "INT_BACKOFFICE",
                "INT_ESCALATION");
        assertThat(all).allSatisfy(c -> assertThat(c).containsOnlyKeys("code", "name", "audience",
                "defaultSeverity", "subcategories"));
        Map<String, Object> backOffice = byCode(all, "INT_BACKOFFICE");
        assertThat(backOffice).containsEntry("audience", "INTERNAL").containsEntry("name", "Account services")
                .containsEntry("defaultSeverity", "MEDIUM");
        assertThat(listOf(backOffice, "subcategories")).extracting(s -> s.get("code"), s -> s.get("defaultSeverity"))
                .containsExactly(org.assertj.core.api.Assertions.tuple("INT_BILL_ADJUST", null),
                        org.assertj.core.api.Assertions.tuple("INT_PROVISIONING", "HIGH"));
        assertThat(byCode(all, "INT_ESCALATION")).containsEntry("subcategories", List.of());
        assertThat(byCode(all, "OTHER")).containsEntry("audience", "CUSTOMER");
        // customer categories first, each level by sort order
        assertThat(all.get(all.size() - 1).get("audience")).isEqualTo("INTERNAL");
    }

    @Test
    void withoutCaseCategoryInternalTheInternalCategoriesAreHiddenAndCannotBeUsed() throws Exception {
        Authentication reduced = TestPrincipals.without(userDetails, "T-IN", Permissions.CASE_CATEGORY_INTERNAL);
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofHours(6))); // after every other 403 sampled at T0..T0+30 min
        verify("T-IN", c6);

        List<Map<String, Object>> visible = list(mvc.perform(get(CATEGORIES).with(authentication(reduced)))
                .andExpect(status().isOk()).andReturn());
        assertThat(visible).extracting(c -> c.get("audience")).containsOnly("CUSTOMER");
        assertThat(visible).extracting(c -> c.get("code")).contains("OTHER").doesNotContain("INT_BACKOFFICE");

        long before = casesOf("ACC-T-0006");
        MvcResult denied = mvc.perform(withCsrf(post(casesPath(c6)).with(authentication(reduced)), key())
                .content(json(body("INT_ESCALATION", null)))).andReturn();
        assertProblem(denied, 403, "forbidden", casesPath(c6));
        assertThat(casesOf("ACC-T-0006")).isEqualTo(before);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, outcome, details FROM audit_events
                WHERE action = 'ACCESS_DENIED' AND correlation_id = :cid
                """, Map.of("cid", denied.getResponse().getHeader("X-Correlation-Id")));
        assertThat(audit).containsEntry("actor_username", "T-IN").containsEntry("outcome", "DENIED");
        assertThat(map((String) audit.get("details"))).containsEntry("reason", "permission")
                .containsEntry("path", casesPath(c6));

        // a customer category still works for that principal
        mvc.perform(withCsrf(post(casesPath(c6)).with(authentication(reduced)), key())
                .content(json(body("OTHER", null)))).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ I12

    @Test
    void createsOnBehalfWithAgentChannelPrimaryContactAndAnInternalNote() throws Exception {
        long check = verify("T-IN", c6);
        Map<String, Object> request = body("OTHER", null);
        request.put("agentNotes", "Customer was polite; asked for a call back after 6 pm.");
        request.put("accountId", 1);                    // ignored
        request.put("channel", "CUSTOMER_PORTAL");      // ignored
        request.put("contactMsisdn", "+919900000001");  // ignored

        Map<String, Object> created = map(create(login("T-IN"), c6, key(), request)
                .andExpect(status().isCreated()).andReturn());

        assertThat(created).containsOnlyKeys("reference", "caseType", "status", "severity", "createdAt",
                "contactMsisdn", "accountRef", "channel");
        String reference = (String) created.get("reference");
        assertThat(reference).matches("SR-20300115-\\d{6,}");
        assertThat(created).containsEntry("caseType", "REQUEST").containsEntry("status", "CREATED")
                .containsEntry("severity", "LOW").containsEntry("contactMsisdn", "+919900000006")
                .containsEntry("accountRef", "ACC-T-0006").containsEntry("channel", "INBOUND_AGENT");

        Map<String, Object> row = caseRow(reference);
        assertThat(row).containsEntry("channel", "INBOUND_AGENT").containsEntry("creator", "T-IN")
                .containsEntry("account_ref", "ACC-T-0006").containsEntry("sub_msisdn", "+919900000006")
                .containsEntry("contact_msisdn", "+919900000006").containsEntry("category", "OTHER")
                .containsEntry("subject", "Customer cannot top up online")
                .containsEntry("description", "The customer says the top-up page fails at the payment step.")
                .containsEntry("preferred_contact", "SMS").containsEntry("severity", "LOW");
        assertDueTimes(row, "OTHER", "LOW");

        assertThat(activity(reference)).containsExactly(
                Map.of("activity_type", "CREATED", "visibility", "PUBLIC", "username", "T-IN", "note", ""),
                Map.of("activity_type", "NOTE", "visibility", "INTERNAL", "username", "T-IN",
                        "note", "Customer was polite; asked for a call back after 6 pm."));

        Map<String, Object> details = auditDetails(reference);
        assertThat(details).containsEntry("category", "OTHER").containsEntry("caseType", "REQUEST")
                .containsEntry("severity", "LOW").containsEntry("channel", "INBOUND_AGENT")
                .containsEntry("customerRef", "CUST-T-0006").containsEntry("checkId", (int) check)
                .containsEntry("internalCategory", false).containsEntry("severityDerived", "LOW")
                .containsEntry("agentNote", true).doesNotContainKey("severityReason");
        assertThat(details.toString()).doesNotContain("call back");
    }

    @Test
    void aChosenSubscriptionIsTheContactAndForeignOrTerminatedOnesAre404() throws Exception {
        verify("T-IN", c6);
        verify("T-IN", c3);
        Cookie agent = login("T-IN");
        Map<String, Object> request = body("NETWORK", "NO_SIGNAL");
        request.put("subscriptionId", subscriptionId("+919900000016"));

        Map<String, Object> created = map(create(agent, c6, key(), request).andExpect(status().isCreated())
                .andReturn());
        assertThat(created).containsEntry("contactMsisdn", "+919900000016").containsEntry("caseType", "COMPLAINT");
        assertThat(caseRow((String) created.get("reference"))).containsEntry("sub_msisdn", "+919900000016")
                .containsEntry("contact_msisdn", "+919900000016");

        request.put("subscriptionId", subscriptionId("+919900000001")); // CUST-T-0001's
        assertProblem(create(agent, c6, key(), request).andReturn(), 404, "not-found", casesPath(c6));
        request.put("subscriptionId", subscriptionId("+919900000003")); // TERMINATED (and CLOSED account)
        assertProblem(create(agent, c3, key(), request).andReturn(), 404, "not-found", casesPath(c3));
    }

    @Test
    void internalCategoryWithThePermissionIsAudited() throws Exception {
        verify("T-IN", c6);

        Map<String, Object> created = map(create(login("T-IN"), c6, key(), body("INT_BACKOFFICE", "INT_BILL_ADJUST"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(created).containsEntry("severity", "MEDIUM"); // leaf has none: the parent's
        Map<String, Object> row = caseRow((String) created.get("reference"));
        assertThat(row).containsEntry("category", "INT_BILL_ADJUST");
        assertDueTimes(row, "INT_BACKOFFICE", "MEDIUM");
        assertThat(auditDetails((String) created.get("reference"))).containsEntry("internalCategory", true)
                .containsEntry("agentNote", false);
    }

    @Test
    void severityOverrideNeedsThePermissionAndAReasonAndUsesTheFinalSeveritysSla() throws Exception {
        verify("T-IN", c6);
        Cookie agent = login("T-IN");
        Map<String, Object> request = body("OTHER", null);
        request.put("severity", "CRITICAL");

        assertProblem(create(agent, c6, key(), request).andReturn(), 400, "bad-request", casesPath(c6));
        request.put("severityReason", "   ab   ");
        assertProblem(create(agent, c6, key(), request).andReturn(), 400, "bad-request", casesPath(c6));
        request.put("severityReason", "abc");
        assertProblem(create(agent, c6, key(), request).andReturn(), 400, "validation", casesPath(c6));

        request.put("severityReason", "Customer is a hospital line; outage affects patients.");
        Map<String, Object> created = map(create(agent, c6, key(), request).andExpect(status().isCreated())
                .andReturn());
        assertThat(created).containsEntry("severity", "CRITICAL");
        Map<String, Object> row = caseRow((String) created.get("reference"));
        assertThat(row).containsEntry("severity", "CRITICAL");
        assertDueTimes(row, "OTHER", "CRITICAL");
        assertThat(auditDetails((String) created.get("reference"))).containsEntry("severity", "CRITICAL")
                .containsEntry("severityDerived", "LOW")
                .containsEntry("severityReason", "Customer is a hospital line; outage affects patients.");

        Authentication noOverride = TestPrincipals.without(userDetails, "T-IN", Permissions.CASE_SEVERITY_OVERRIDE);
        assertProblem(mvc.perform(withCsrf(post(casesPath(c6)).with(authentication(noOverride)), key())
                .content(json(request))).andReturn(), 403, "forbidden", casesPath(c6));
        // the derived severity, spelled out, is no override
        request.put("severity", "LOW");
        request.remove("severityReason");
        mvc.perform(withCsrf(post(casesPath(c6)).with(authentication(noOverride)), key()).content(json(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void noSlaRowForTheFinalSeverityIs422() throws Exception {
        verify("T-IN", c6);
        jdbc.update("""
                INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active,
                                        sort_order, created_at, updated_at)
                VALUES ('T_AGENT_NOSLA', 'No SLA', NULL, 'CUSTOMER', 'REQUEST', 'LOW', TRUE, 999,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of());
        long before = casesOf("ACC-T-0006");

        assertProblem(create(login("T-IN"), c6, key(), body("T_AGENT_NOSLA", null)).andReturn(), 422,
                "business-rule", casesPath(c6));
        assertThat(casesOf("ACC-T-0006")).isEqualTo(before);
    }

    @Test
    void contactAndAccountStateRulesAre422() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        verify("T-IN", c7);
        verify("T-IN", c8);
        verify("T-IN", c3);
        verify("T-IN", c9);

        Map<String, Object> email = body("OTHER", null);
        email.put("preferredContact", "EMAIL");
        assertProblem(create(agent, c7, key(), email).andReturn(), 422, "business-rule", casesPath(c7));
        Map<String, Object> ok = map(create(agent, c6, key(), email).andExpect(status().isCreated()).andReturn());
        assertThat(caseRow((String) ok.get("reference"))).containsEntry("preferred_contact", "EMAIL");

        assertProblem(create(agent, c8, key(), body("OTHER", null)).andReturn(), 422, "business-rule",
                casesPath(c8));
        assertProblem(create(agent, c3, key(), body("OTHER", null)).andReturn(), 422, "business-rule",
                casesPath(c3));
        assertProblem(create(agent, c6, key(), body("NOPE", null)).andReturn(), 422, "business-rule",
                casesPath(c6));
        assertProblem(create(agent, c6, key(), body("NETWORK", null)).andReturn(), 422, "business-rule",
                casesPath(c6));

        jdbc.update("""
                UPDATE subscriptions s JOIN accounts a ON a.id = s.account_id
                SET s.is_primary = FALSE WHERE a.account_ref = 'ACC-T-0009'
                """, Map.of());
        assertProblem(create(agent, c9, key(), body("OTHER", null)).andReturn(), 422, "business-rule",
                casesPath(c9));
        Map<String, Object> chosen = body("OTHER", null);
        chosen.put("subscriptionId", subscriptionId("+919900000009"));
        assertThat(map(create(agent, c9, key(), chosen).andExpect(status().isCreated()).andReturn()))
                .containsEntry("contactMsisdn", "+919900000009");
    }

    @Test
    void invalidBodiesAndAMissingKeyAre400() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);

        Map<String, Object> shortSubject = body("OTHER", null);
        shortSubject.put("subject", "Hi");
        assertProblem(create(agent, c6, key(), shortSubject).andReturn(), 400, "validation", casesPath(c6));
        Map<String, Object> noStatement = body("OTHER", null);
        noStatement.remove("customerStatement");
        assertProblem(create(agent, c6, key(), noStatement).andReturn(), 400, "validation", casesPath(c6));
        Map<String, Object> badSeverity = body("OTHER", null);
        badSeverity.put("severity", "URGENT");
        assertThat(create(agent, c6, key(), badSeverity).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertProblem(create(agent, c6, null, body("OTHER", null)).andReturn(), 400, "bad-request", casesPath(c6));
    }

    @Test
    void replayReturnsTheSameCaseAndTheHashCoversBodyAndCustomer() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        verify("T-IN", c7);
        String key = key();
        long before = casesOf("ACC-T-0006");

        Map<String, Object> first = map(create(agent, c6, key, body("OTHER", null)).andExpect(status().isCreated())
                .andReturn());
        Map<String, Object> replay = map(create(agent, c6, key, body("OTHER", null)).andExpect(status().isCreated())
                .andReturn());
        assertThat(replay).isEqualTo(first);
        assertThat(casesOf("ACC-T-0006")).isEqualTo(before + 1);

        Map<String, Object> changed = body("OTHER", null);
        changed.put("subject", "A different summary");
        assertProblem(create(agent, c6, key, changed).andReturn(), 422, "idempotency-mismatch", casesPath(c6));
        // same key and body for another verified customer: the path variable is part of the hash
        assertProblem(create(agent, c7, key, body("OTHER", null)).andReturn(), 422, "idempotency-mismatch",
                casesPath(c7));
    }

    @Test
    void aReplayAfterTheCheckExpiredIsVerificationRequired() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        String key = key();
        create(agent, c6, key, body("OTHER", null)).andExpect(status().isCreated());

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(30)));
        assertProblem(create(agent, c6, key, body("OTHER", null)).andReturn(), 403, "verification-required",
                casesPath(c6));
    }

    // ------------------------------------------------------------------ scope and authorization

    @Test
    void withoutAValidCheckCreateIsVerificationRequired() throws Exception {
        verify("T-IN", c7); // a check for another customer does not help
        long before = casesOf("ACC-T-0006");

        assertProblem(create(login("T-IN"), c6, key(), body("OTHER", null)).andReturn(), 403,
                "verification-required", casesPath(c6));
        assertThat(casesOf("ACC-T-0006")).isEqualTo(before);
    }

    @Test
    void anonymousIs401AndRolesWithoutCustomerLookupAre403Forbidden() throws Exception {
        verify("T-IN", c6);
        assertProblem(mvc.perform(get(CATEGORIES)).andReturn(), 401, "unauthenticated", CATEGORIES);
        assertProblem(mvc.perform(withCsrf(post(casesPath(c6)), key()).content(json(body("OTHER", null))))
                .andReturn(), 401, "unauthenticated", casesPath(c6));
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(user);
            assertProblem(mvc.perform(get(CATEGORIES).cookie(session)).andReturn(), 403, "forbidden", CATEGORIES);
            assertProblem(create(session, c6, key(), body("OTHER", null)).andReturn(), 403, "forbidden",
                    casesPath(c6));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A valid request: SMS contact, no subscription, derived severity, no note. */
    private static Map<String, Object> body(String categoryCode, String subcategoryCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryCode", categoryCode);
        body.put("subcategoryCode", subcategoryCode);
        body.put("subject", "Customer cannot top up online");
        body.put("customerStatement", "The customer says the top-up page fails at the payment step.");
        body.put("preferredContact", "SMS");
        return body;
    }

    private ResultActions create(Cookie session, long customerId, String key, Map<String, Object> body)
            throws Exception {
        return mvc.perform(withCsrf(post(casesPath(customerId)).cookie(session), key).content(json(body)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, String key) {
        MockHttpServletRequestBuilder r = request.cookie(new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON);
        return key == null ? r : r.header("Idempotency-Key", key);
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private static String casesPath(long customerId) {
        return "/api/v1/agent/customers/" + customerId + "/cases";
    }

    private Map<String, Object> caseRow(String reference) {
        return jdbc.queryForMap("""
                SELECT c.channel, c.status, c.severity, c.preferred_contact, c.contact_msisdn, c.subject,
                       c.description, cat.code AS category, a.account_ref, u.username AS creator, s.msisdn AS sub_msisdn,
                       c.created_at, c.response_due_at, c.sla_due_at
                FROM cases c
                JOIN categories cat ON cat.id = c.category_id
                JOIN accounts a ON a.id = c.account_id
                JOIN users u ON u.id = c.created_by_user_id
                LEFT JOIN subscriptions s ON s.id = c.subscription_id
                WHERE c.reference = :ref
                """, Map.of("ref", reference));
    }

    /** Due times = created_at + the SLA minutes of (slaCategory, severity). */
    private void assertDueTimes(Map<String, Object> row, String slaCategory, String severity) {
        Map<String, Object> sla = jdbc.queryForMap("""
                SELECT p.response_minutes, p.resolution_minutes
                FROM sla_policies p JOIN categories c ON c.id = p.category_id
                WHERE c.code = :code AND p.severity = :severity
                """, Map.of("code", slaCategory, "severity", severity));
        LocalDateTime created = (LocalDateTime) row.get("created_at");
        assertThat(Duration.between(created, (LocalDateTime) row.get("response_due_at")).toMinutes())
                .isEqualTo(((Number) sla.get("response_minutes")).longValue());
        assertThat(Duration.between(created, (LocalDateTime) row.get("sla_due_at")).toMinutes())
                .isEqualTo(((Number) sla.get("resolution_minutes")).longValue());
    }

    private List<Map<String, Object>> activity(String reference) {
        return jdbc.queryForList("""
                SELECT a.activity_type, a.visibility, u.username, COALESCE(a.note_text, '') AS note
                FROM case_activity a
                JOIN cases c ON c.id = a.case_id
                JOIN users u ON u.id = a.actor_user_id
                WHERE c.reference = :ref
                ORDER BY a.created_at, a.id
                """, Map.of("ref", reference));
    }

    private Map<String, Object> auditDetails(String reference) throws Exception {
        String details = jdbc.queryForObject("""
                SELECT details FROM audit_events WHERE action = 'CASE_CREATED' AND entity_id = :ref
                """, Map.of("ref", reference), String.class);
        return map(details);
    }

    private long casesOf(String accountRef) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cases c JOIN accounts a ON a.id = c.account_id WHERE a.account_ref = :ref
                """, Map.of("ref", accountRef), Long.class);
    }

    private long subscriptionId(String msisdn) {
        return jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = :m", Map.of("m", msisdn), Long.class);
    }

    /** A KBA check of {@code username} for the customer, VERIFIED now for 30 minutes. */
    private long verify(String username, long customerId) {
        Instant now = InboundTestClock.CLOCK.instant();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, verified_at,
                                             valid_until, created_at, updated_at)
                VALUES (:agent, :customer, 'KBA', 'VERIFIED', 1, :t, :validUntil, :t, :t)
                """, new MapSqlParameterSource()
                .addValue("agent", userId(username))
                .addValue("customer", customerId)
                .addValue("t", utc(now))
                .addValue("validUntil", utc(now.plus(Duration.ofMinutes(30)))), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    private static Map<String, Object> byCode(List<Map<String, Object>> categories, String code) {
        return categories.stream().filter(c -> code.equals(c.get("code"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Map<String, Object> map(MvcResult result) throws Exception {
        return map(result.getResponse().getContentAsString());
    }

    private Map<String, Object> map(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private List<Map<String, Object>> list(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
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
        String ip = "10.36." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", TEST_PASSWORD))))
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
