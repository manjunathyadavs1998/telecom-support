package com.telecom.support.servicecase;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Agent case list I9 and detail I10 (design 03-inbound backend R5, R8–R10, §4, §9; INB-02, BR-04, XC-04/05). Test
 * data: CUST-T-0006 has two INBOUND_AGENT cases by T-IN — 900101 (INT_BILL_ADJUST, CREATED, now−3 d) and 900102
 * (INT_ESCALATION, IN_PROGRESS, now−1 d) — each with a PUBLIC CREATED row and an INTERNAL note. The shared inbound
 * clock is set to the real time (test data is relative to it). Other ITs may add cases on CUST-T-0006, so lists are
 * checked by the test-data references and duplicates around a case inserted here 200 days ago.
 */
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class AgentCaseQueryIT extends AbstractMySqlIT {

    private static final String XSRF = "case-query-xsrf-token";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final String NOTE = "Internal: customer disputes charges; check billing history before replying.";

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AppUserDetailsService userDetails;

    private final List<Long> checks = new ArrayList<>();
    private final List<String> insertedCases = new ArrayList<>();
    private long c1;
    private long c6;
    private String ref101;
    private String ref102;

    @BeforeEach
    void reset() {
        InboundTestClock.CLOCK.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        c1 = customerId("CUST-T-0001");
        c6 = customerId("CUST-T-0006");
        ref101 = testReference("900101");
        ref102 = testReference("900102");
    }

    @AfterEach
    void cleanUp() {
        if (!checks.isEmpty()) {
            jdbc.update("DELETE FROM identity_checks WHERE id IN (:ids)", Map.of("ids", checks));
            checks.clear();
        }
        if (!insertedCases.isEmpty()) {
            jdbc.update("DELETE FROM cases WHERE reference IN (:refs)", Map.of("refs", insertedCases));
            insertedCases.clear();
        }
        jdbc.update("UPDATE cases SET assigned_user_id = NULL, assigned_team_id = NULL WHERE reference IN (:refs)",
                Map.of("refs", List.of(ref101, ref102)));
        InboundTestClock.CLOCK.set(InboundTestClock.T0);
    }

    // ------------------------------------------------------------------ I9

    @Test
    void listShowsTheAccountsCasesNewestFirstWithInternalAndPublicStatus() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);

        Map<String, Object> page = json(cases(agent, c6, "").andExpect(status().isOk()).andReturn());

        assertThat(page).containsKeys("items", "page", "size", "totalItems", "totalPages")
                .containsEntry("page", 0).containsEntry("size", 20);
        List<Map<String, Object>> mine = testItems(page);
        assertThat(mine).extracting(i -> i.get("reference")).containsExactly(ref102, ref101);
        Map<String, Object> escalation = mine.get(0);
        assertThat(escalation).containsOnlyKeys("reference", "categoryName", "subcategoryName", "subject", "caseType",
                "severity", "status", "publicStatus", "channel", "ownerName", "createdAt", "updatedAt");
        assertThat(escalation).containsEntry("categoryName", "Escalated complaint")
                .containsEntry("subcategoryName", null).containsEntry("caseType", "COMPLAINT")
                .containsEntry("severity", "HIGH").containsEntry("status", "IN_PROGRESS")
                .containsEntry("publicStatus", "IN_PROGRESS").containsEntry("channel", "INBOUND_AGENT")
                .containsEntry("ownerName", null).containsEntry("subject", "Escalated service complaint");
        assertThat(mine.get(1)).containsEntry("categoryName", "Account services")
                .containsEntry("subcategoryName", "Bill adjustment").containsEntry("status", "CREATED")
                .containsEntry("publicStatus", "SUBMITTED").containsEntry("severity", "MEDIUM");
        assertThat(items(page)).allSatisfy(i -> assertThat(accountOf((String) i.get("reference")))
                .isEqualTo("ACC-T-0006"));
    }

    @Test
    void ownerIsTheAssignedUserElseTheTeamElseNull() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        jdbc.update("""
                UPDATE cases SET assigned_team_id = (SELECT id FROM teams WHERE code = 'INBOUND'),
                                 assigned_user_id = (SELECT id FROM users WHERE username = 'T-IN-REACT')
                WHERE reference = :ref
                """, Map.of("ref", ref102));
        jdbc.update("UPDATE cases SET assigned_team_id = (SELECT id FROM teams WHERE code = 'INBOUND') "
                + "WHERE reference = :ref", Map.of("ref", ref101));

        List<Map<String, Object>> mine = testItems(json(cases(agent, c6, "").andExpect(status().isOk()).andReturn()));

        assertThat(mine).extracting(i -> i.get("ownerName")).containsExactly("Test Inbound Approver", "Inbound");
        assertThat(json(detail(agent, c6, ref101).andExpect(status().isOk()).andReturn()))
                .containsEntry("ownerName", "Inbound");
    }

    @Test
    void filtersByStatusReferenceAndWindowAndSortsByTheWhitelist() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        LocalDate today = LocalDate.now(IST);

        Map<String, Object> created = json(cases(agent, c6, "?status=CREATED").andExpect(status().isOk()).andReturn());
        assertThat(testItems(created)).extracting(i -> i.get("reference")).containsExactly(ref101);
        assertThat(items(created)).extracting(i -> i.get("status")).containsOnly("CREATED");

        Map<String, Object> both = json(cases(agent, c6, "?status=CREATED&status=IN_PROGRESS&sort=createdAt,asc")
                .andExpect(status().isOk()).andReturn());
        assertThat(testItems(both)).extracting(i -> i.get("reference")).containsExactly(ref101, ref102);

        Map<String, Object> byRef = json(cases(agent, c6, "?reference=" + ref101).andExpect(status().isOk())
                .andReturn());
        assertThat(items(byRef)).extracting(i -> i.get("reference")).containsExactly(ref101);
        assertThat(byRef).containsEntry("totalItems", 1).containsEntry("totalPages", 1);

        Map<String, Object> older = json(cases(agent, c6, "?to=" + today.minusDays(2)).andExpect(status().isOk())
                .andReturn());
        assertThat(testItems(older)).extracting(i -> i.get("reference")).containsExactly(ref101);
        // an exact reference does not bypass the window
        assertThat(items(json(cases(agent, c6, "?reference=" + ref102 + "&to=" + today.minusDays(2))
                .andExpect(status().isOk()).andReturn()))).isEmpty();

        for (String sort : List.of("updatedAt,desc", "updatedAt,asc")) {
            List<Instant> updated = items(json(cases(agent, c6, "?sort=" + sort).andExpect(status().isOk())
                    .andReturn())).stream().map(i -> Instant.parse((String) i.get("updatedAt"))).toList();
            Comparator<Instant> order = sort.endsWith("desc") ? Comparator.reverseOrder() : Comparator.naturalOrder();
            assertThat(updated).isSortedAccordingTo(order);
        }

        Map<String, Object> paged = json(cases(agent, c6, "?status=CREATED&status=IN_PROGRESS&from="
                + today.minusDays(4) + "&to=" + today.minusDays(1) + "&size=1&page=1").andExpect(status().isOk())
                .andReturn());
        assertThat(paged).containsEntry("size", 1).containsEntry("page", 1);
        assertThat(((Number) paged.get("totalItems")).longValue()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void invalidListParametersAre400() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        LocalDate today = LocalDate.now(IST);
        String path = path(c6, "cases");

        assertProblem(cases(agent, c6, "?sort=severity,desc").andReturn(), 400, "bad-request", path);
        assertProblem(cases(agent, c6, "?status=OPEN").andReturn(), 400, "bad-request", path);
        assertProblem(cases(agent, c6, "?from=" + today + "&to=" + today.minusDays(1)).andReturn(), 400,
                "bad-request", path);
        assertProblem(cases(agent, c6, "?from=" + today.minusDays(400) + "&to=" + today).andReturn(), 400,
                "bad-request", path);
        assertThat(cases(agent, c6, "?reference=PAY-20261125-000001").andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(cases(agent, c6, "?size=101").andReturn().getResponse().getStatus()).isEqualTo(400);
        String nineStatuses = "?status=CREATED" + "&status=CREATED".repeat(8);
        assertThat(cases(agent, c6, nineStatuses).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ I10

    @Test
    void detailHasTheTimelineOldestFirstIncludingInternalNotesForAgentsWithTheirPermission() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);

        Map<String, Object> detail = json(detail(agent, c6, ref101).andExpect(status().isOk()).andReturn());

        assertThat(detail).containsOnlyKeys("reference", "categoryName", "subcategoryName", "subject", "caseType",
                "severity", "status", "publicStatus", "channel", "ownerName", "createdAt", "updatedAt", "description",
                "preferredContact", "contactMsisdn", "createdByName", "slaDueAt", "activity", "possibleDuplicates");
        assertThat(detail).containsEntry("reference", ref101)
                .containsEntry("description", "Customer says the last bill includes charges after the suspension.")
                .containsEntry("preferredContact", "SMS").containsEntry("contactMsisdn", "+919900000006")
                .containsEntry("createdByName", "Test Inbound Agent").containsEntry("channel", "INBOUND_AGENT");
        assertThat(detail.get("slaDueAt")).isNotNull();
        List<Map<String, Object>> activity = listOf(detail, "activity");
        assertThat(activity).hasSize(2);
        assertThat(activity.get(0)).containsEntry("type", "CREATED").containsEntry("visibility", "PUBLIC")
                .containsEntry("actorName", "Test Inbound Agent").containsEntry("newValue", "CREATED")
                .containsEntry("note", null);
        assertThat(activity.get(1)).containsEntry("type", "NOTE").containsEntry("visibility", "INTERNAL")
                .containsEntry("actorName", "Test Inbound Agent").containsEntry("note", NOTE);
        assertThat(Instant.parse((String) activity.get(0).get("at")))
                .isBefore(Instant.parse((String) activity.get(1).get("at")));
    }

    @Test
    void withoutCaseNoteInternalTheInternalEntriesAreLeftOut() throws Exception {
        verify("T-IN", c6);
        var reduced = authentication(TestPrincipals.without(userDetails, "T-IN", Permissions.CASE_NOTE_INTERNAL));

        MvcResult result = mvc.perform(get(path(c6, "cases/" + ref101)).with(reduced)).andExpect(status().isOk())
                .andReturn();

        Map<String, Object> detail = json(result);
        assertThat(listOf(detail, "activity")).extracting(a -> a.get("type"), a -> a.get("visibility"))
                .containsExactly(tuple("CREATED", "PUBLIC"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(NOTE, "INTERNAL");
    }

    @Test
    void aCaseOfAnotherAccountOrAnUnknownReferenceIs404() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1);
        verify("T-IN", c6);

        assertProblem(detail(agent, c1, ref101).andReturn(), 404, "not-found", path(c1, "cases/" + ref101));
        assertProblem(detail(agent, c6, "SR-20000101-999999").andReturn(), 404, "not-found",
                path(c6, "cases/SR-20000101-999999"));
        assertThat(items(json(cases(agent, c1, "?reference=" + ref101).andExpect(status().isOk()).andReturn())))
                .isEmpty();
    }

    @Test
    void possibleDuplicatesAreUpToFiveOtherCasesOfTheLeafWithinThirtyDaysNewestFirst() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c6);
        Instant base = InboundTestClock.CLOCK.instant().minus(Duration.ofDays(200));
        String baseRef = insertCase("900190", "INT_PROVISIONING", base);
        insertCase("900191", "INT_PROVISIONING", base.minus(Duration.ofDays(30)));   // edge: inside
        insertCase("900192", "INT_PROVISIONING", base.plus(Duration.ofDays(30)));    // edge: inside
        insertCase("900193", "INT_PROVISIONING", base.minus(Duration.ofDays(31)));   // outside
        insertCase("900194", "INT_PROVISIONING", base.plus(Duration.ofDays(31)));    // outside
        insertCase("900195", "INT_PROVISIONING", base.plus(Duration.ofDays(1)));
        insertCase("900196", "INT_PROVISIONING", base.minus(Duration.ofDays(1)));
        insertCase("900197", "INT_PROVISIONING", base.minus(Duration.ofDays(2)));
        insertCase("900198", "INT_BILL_ADJUST", base.plus(Duration.ofDays(2)));      // other leaf
        insertCaseOn("ACC-T-0007", "900199", "INT_PROVISIONING", base);              // other account

        List<Map<String, Object>> duplicates = listOf(json(detail(agent, c6, baseRef).andExpect(status().isOk())
                .andReturn()), "possibleDuplicates");
        assertThat(duplicates).extracting(d -> d.get("reference")).containsExactly(
                ref("900192", base.plus(Duration.ofDays(30))), ref("900195", base.plus(Duration.ofDays(1))),
                ref("900196", base.minus(Duration.ofDays(1))), ref("900197", base.minus(Duration.ofDays(2))),
                ref("900191", base.minus(Duration.ofDays(30))));

        // a sixth match inside the window: the oldest one drops out
        String sixth = insertCase("900189", "INT_PROVISIONING", base.minus(Duration.ofDays(10)));
        Map<String, Object> detail = json(detail(agent, c6, baseRef).andExpect(status().isOk()).andReturn());
        duplicates = listOf(detail, "possibleDuplicates");
        assertThat(duplicates).hasSize(5).extracting(d -> d.get("reference")).contains(sixth)
                .doesNotContain(ref("900191", base.minus(Duration.ofDays(30))));
        assertThat(duplicates.get(0)).containsOnlyKeys("reference", "subject", "status", "createdAt")
                .containsEntry("status", "CREATED");
        assertThat(listOf(detail, "activity")).isEmpty();
    }

    @Test
    void customerPortalListStillShowsOnlyPublicFields() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/customers/me/cases").cookie(login("+919900000001")))
                .andExpect(status().isOk()).andReturn();

        assertThat(items(json(result))).isNotEmpty().allSatisfy(i -> {
            assertThat(i).containsOnlyKeys("reference", "categoryName", "subcategoryName", "subject", "createdAt",
                    "severity", "status");
            assertThat(i.get("status")).isIn("SUBMITTED", "IN_PROGRESS", "AWAITING_YOUR_REPLY", "RESOLVED",
                    "CLOSED");
        });
        assertThat(result.getResponse().getContentAsString()).doesNotContain("INTERNAL", "ownerName", "activity");
    }

    // ------------------------------------------------------------------ scope and authorization

    @Test
    void withoutAValidCheckBothRoutesAreVerificationRequired() throws Exception {
        Cookie agent = login("T-IN");
        verify("T-IN", c1); // a check for another customer does not help

        assertProblem(cases(agent, c6, "").andReturn(), 403, "verification-required", path(c6, "cases"));
        assertProblem(detail(agent, c6, ref101).andReturn(), 403, "verification-required",
                path(c6, "cases/" + ref101));
    }

    @Test
    void anonymousIs401AndRolesWithoutCustomerLookupAre403Forbidden() throws Exception {
        verify("T-IN", c6);
        assertProblem(mvc.perform(get(path(c6, "cases"))).andReturn(), 401, "unauthenticated", path(c6, "cases"));
        assertProblem(mvc.perform(get(path(c6, "cases/" + ref101))).andReturn(), 401, "unauthenticated",
                path(c6, "cases/" + ref101));
        for (String user : List.of("+919900000001", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD")) {
            Cookie session = login(user);
            assertProblem(cases(session, c6, "").andReturn(), 403, "forbidden", path(c6, "cases"));
            assertProblem(detail(session, c6, ref101).andReturn(), 403, "forbidden", path(c6, "cases/" + ref101));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions cases(Cookie session, long customerId, String query) throws Exception {
        return mvc.perform(get(path(customerId, "cases") + query).cookie(session));
    }

    private ResultActions detail(Cookie session, long customerId, String reference) throws Exception {
        MockHttpServletRequestBuilder request = get(path(customerId, "cases/" + reference)).cookie(session);
        return mvc.perform(request);
    }

    private static String path(long customerId, String route) {
        return "/api/v1/agent/customers/" + customerId + "/" + route;
    }

    /** {@code SR-<IST date of created_at>-<seq>} of a test-data case of CUST-T-0006. */
    private String testReference(String seq) {
        return jdbc.queryForObject("SELECT reference FROM cases WHERE reference LIKE :ref",
                Map.of("ref", "SR-%-" + seq), String.class);
    }

    private static String ref(String seq, Instant createdAt) {
        return "SR-" + LocalDate.ofInstant(createdAt, IST).toString().replace("-", "") + "-" + seq;
    }

    private String insertCase(String seq, String categoryCode, Instant createdAt) {
        return insertCaseOn("ACC-T-0006", seq, categoryCode, createdAt);
    }

    /** A bare case (no activity, so it can be deleted again) by T-IN on the account's primary subscription. */
    private String insertCaseOn(String accountRef, String seq, String categoryCode, Instant createdAt) {
        String reference = ref(seq, createdAt);
        jdbc.update("""
                INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject,
                                   description, severity, status, preferred_contact, contact_msisdn,
                                   created_by_user_id, response_due_at, sla_due_at, created_at, updated_at)
                SELECT :ref, a.id, s.id, cat.id, cat.case_type, 'INBOUND_AGENT', CONCAT('Duplicate probe ', :seq),
                       'Inserted by AgentCaseQueryIT.', 'HIGH', 'CREATED', 'SMS', s.msisdn, u.id, :t, :t, :t, :t
                FROM accounts a
                JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
                JOIN categories cat ON cat.code = :category
                JOIN users u ON u.username = 'T-IN'
                WHERE a.account_ref = :account
                """, new MapSqlParameterSource()
                .addValue("ref", reference)
                .addValue("seq", seq)
                .addValue("category", categoryCode)
                .addValue("account", accountRef)
                .addValue("t", utc(createdAt)));
        insertedCases.add(reference);
        return reference;
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
        long id = keys.getKey().longValue();
        checks.add(id);
        return id;
    }

    private String accountOf(String reference) {
        return jdbc.queryForObject("""
                SELECT a.account_ref FROM cases c JOIN accounts a ON a.id = c.account_id WHERE c.reference = :ref
                """, Map.of("ref", reference), String.class);
    }

    /** The items whose reference is one of the CUST-T-0006 test-data cases, in list order. */
    private List<Map<String, Object>> testItems(Map<String, Object> page) {
        return items(page).stream()
                .filter(i -> List.of(ref101, ref102).contains(i.get("reference")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
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
        String ip = "10.35." + (n / 250) + "." + (n % 250 + 1);
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
