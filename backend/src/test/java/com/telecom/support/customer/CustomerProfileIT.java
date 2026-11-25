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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E1/E2 {@code /api/v1/customers/me/profile} end to end (design 02-customer backend R1, R2, R16, §4, §7, §9;
 * CUS-01.1, CUS-02.1, CUS-02.3–02.5, BR-10). Test data: R__test_users_02_customer.sql.
 */
class CustomerProfileIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/profile";
    private static final String XSRF = "profile-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /** Test data has no aliases on customers 1 and 3 and "Two" on customer 2; restore after every test. */
    @AfterEach
    void restoreAliases() {
        jdbc.update("UPDATE customers SET alias_name = NULL WHERE customer_ref IN ('CUST-T-0001', 'CUST-T-0003')",
                Map.of());
        jdbc.update("UPDATE customers SET alias_name = 'Two' WHERE customer_ref = 'CUST-T-0002'", Map.of());
    }

    // ------------------------------------------------------------------ E1

    @Test
    void readsOwnProfile() throws Exception {
        Cookie session = login("+919900000001");

        MvcResult result = mvc.perform(get(PATH).cookie(session)).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> body = json(result);
        assertThat(body).containsOnlyKeys("customerRef", "accountRef", "fullName", "aliasName", "primaryMsisdn",
                "secondaryMsisdn", "secondaryMsisdnVerified", "email", "emailVerified", "billingAddress", "version");
        assertThat(body.get("customerRef")).isEqualTo("CUST-T-0001");
        assertThat(body.get("accountRef")).isEqualTo("ACC-T-0001");
        assertThat(body.get("fullName")).isEqualTo("Test Customer");
        assertThat(body.get("aliasName")).isNull();
        assertThat(body.get("primaryMsisdn")).isEqualTo("+919900000001");
        assertThat(body.get("email")).isEqualTo("test.customer1@example.test");
        assertThat(body.get("emailVerified")).isEqualTo(true);
        assertThat(body.get("secondaryMsisdn")).isNull();
        assertThat(body.get("secondaryMsisdnVerified")).isEqualTo(false);
        assertThat(body.get("version")).isEqualTo(version("CUST-T-0001"));
        assertThat(body.get("billingAddress")).isEqualTo(address("1 Test Street", "Pune", "MH", "411001"));
    }

    @Test
    void eachCustomerSeesOnlyTheirOwnProfile() throws Exception {
        Map<String, Object> body = json(mvc.perform(get(PATH).cookie(login("+919900000002")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("customerRef")).isEqualTo("CUST-T-0002");
        assertThat(body.get("accountRef")).isEqualTo("ACC-T-0002");
        assertThat(body.get("aliasName")).isEqualTo("Two");
        assertThat(body.get("email")).isNull();
        assertThat(body.get("emailVerified")).isEqualTo(false);
    }

    @Test
    void closedAccountCanStillReadItsProfile() throws Exception {
        Map<String, Object> body = json(mvc.perform(get(PATH).cookie(login("+919900000003")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("accountRef")).isEqualTo("ACC-T-0003");
        assertThat(body.get("primaryMsisdn")).isEqualTo("+919900000003");
    }

    @Test
    void readIsUnauthenticatedForAnonymous() throws Exception {
        assertProblem(mvc.perform(get(PATH)).andReturn(), 401, "unauthenticated", PATH);
    }

    @Test
    void readIsForbiddenForStaffAndUnlinkedCustomers() throws Exception {
        // INBOUND_AGENT holds customer.profile.read but is not a customer (§4); OUTBOUND_AGENT lacks the permission
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(mvc.perform(get(PATH).cookie(login(username))).andReturn(), 403, "forbidden", PATH);
        }
    }

    // ------------------------------------------------------------------ E2

    @Test
    void updatesAliasAndAudits() throws Exception {
        Cookie session = login("+919900000001");
        int version = version("CUST-T-0001");
        long otherVersion = version("CUST-T-0002");

        Map<String, Object> request = new HashMap<>();
        request.put("aliasName", "Ravi K. O'Neil-2");
        request.put("version", version);
        request.put("customerId", customerId("CUST-T-0002")); // ignored (R1)
        request.put("accountId", 999);
        MvcResult result = patchProfile(session, request).andExpect(status().isOk()).andReturn();

        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        Map<String, Object> body = json(result);
        assertThat(body.get("customerRef")).isEqualTo("CUST-T-0001");
        assertThat(body.get("aliasName")).isEqualTo("Ravi K. O'Neil-2");
        assertThat(body.get("version")).isEqualTo(version + 1);
        assertThat(alias("CUST-T-0001")).isEqualTo("Ravi K. O'Neil-2");
        assertThat(alias("CUST-T-0002")).isEqualTo("Two");
        assertThat((long) version("CUST-T-0002")).isEqualTo(otherVersion);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, entity_type, entity_id, outcome, details
                FROM audit_events WHERE action = 'PROFILE_UPDATED' AND correlation_id = :cid
                """, Map.of("cid", correlationId));
        assertThat(audit.get("actor_username")).isEqualTo("+919900000001");
        assertThat(audit.get("entity_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("entity_id")).isEqualTo(String.valueOf(customerId("CUST-T-0001")));
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        Map<String, Object> details = objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
        Map<String, Object> change = new HashMap<>();
        change.put("old", null);
        change.put("new", "Ravi K. O'Neil-2");
        assertThat(details).isEqualTo(Map.of("aliasName", change));
    }

    @Test
    void nullAliasClearsIt() throws Exception {
        Cookie session = login("+919900000001");
        Map<String, Object> set = json(patchProfile(session, alias("Ravi", version("CUST-T-0001")))
                .andExpect(status().isOk()).andReturn());

        Map<String, Object> cleared = json(patchProfile(session, alias(null, (Integer) set.get("version")))
                .andExpect(status().isOk()).andReturn());

        assertThat(cleared).containsEntry("aliasName", null);
        assertThat(alias("CUST-T-0001")).isNull();
        assertThat(auditCount("PROFILE_UPDATED", "CUST-T-0001")).isPositive();
    }

    @Test
    void missingAliasKeyIsAValidationError() throws Exception {
        Cookie session = login("+919900000001");
        int version = version("CUST-T-0001");

        Map<String, Object> body = assertProblem(patchProfile(session, Map.of("version", version)).andReturn(),
                400, "validation", PATH);

        assertThat(fields(body)).containsExactly("aliasName");
        assertThat(version("CUST-T-0001")).isEqualTo(version);
    }

    @Test
    void invalidAliasValuesAreValidationErrors() throws Exception {
        Cookie session = login("+919900000001");
        int version = version("CUST-T-0001");

        for (String bad : List.of("", "x".repeat(61), "Ravi<script>", "Ravi_1", "a@b")) {
            Map<String, Object> body = assertProblem(patchProfile(session, alias(bad, version)).andReturn(),
                    400, "validation", PATH);
            assertThat(fields(body)).as(bad).isNotEmpty().allMatch(f -> f.startsWith("aliasName"));
        }
        assertThat(json(patchProfile(session, alias("x".repeat(60), version)).andExpect(status().isOk())
                .andReturn()).get("aliasName")).isEqualTo("x".repeat(60));
    }

    @Test
    void missingVersionIsAValidationError() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("aliasName", "Ravi");
        Map<String, Object> body = assertProblem(patchProfile(login("+919900000001"), request).andReturn(),
                400, "validation", PATH);

        assertThat(fields(body)).containsExactly("version");
    }

    @Test
    void staleVersionIsAConflictAndChangesNothing() throws Exception {
        Cookie session = login("+919900000001");
        int version = version("CUST-T-0001");
        long audits = auditCount("PROFILE_UPDATED", "CUST-T-0001");

        assertProblem(patchProfile(session, alias("Stale", version - 1)).andReturn(), 409, "conflict", PATH);
        assertProblem(patchProfile(session, alias("Future", version + 5)).andReturn(), 409, "conflict", PATH);

        assertThat(alias("CUST-T-0001")).isNull();
        assertThat(version("CUST-T-0001")).isEqualTo(version);
        assertThat(auditCount("PROFILE_UPDATED", "CUST-T-0001")).isEqualTo(audits);
    }

    @Test
    void closedAccountCannotUpdate() throws Exception {
        Cookie session = login("+919900000003");
        int version = version("CUST-T-0003");

        assertProblem(patchProfile(session, alias("Closed", version)).andReturn(), 422, "business-rule", PATH);

        assertThat(alias("CUST-T-0003")).isNull();
        assertThat(version("CUST-T-0003")).isEqualTo(version);
        assertThat(auditCount("PROFILE_UPDATED", "CUST-T-0003")).isZero();
    }

    @Test
    void inactiveCustomerCannotUpdate() throws Exception {
        Cookie session = login("+919900000002");
        int version = version("CUST-T-0002");
        jdbc.update("UPDATE customers SET status = 'INACTIVE' WHERE customer_ref = 'CUST-T-0002'", Map.of());
        try {
            assertProblem(patchProfile(session, alias("Inactive", version)).andReturn(), 422, "business-rule", PATH);
            assertThat(alias("CUST-T-0002")).isEqualTo("Two");
        } finally {
            jdbc.update("UPDATE customers SET status = 'ACTIVE' WHERE customer_ref = 'CUST-T-0002'", Map.of());
        }
    }

    @Test
    void updateIsUnauthenticatedForAnonymous() throws Exception {
        MvcResult result = mvc.perform(patch(PATH)
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(alias("Anon", 0))))
                .andReturn();
        assertProblem(result, 401, "unauthenticated", PATH);
    }

    @Test
    void updateIsForbiddenForStaffAndUnlinkedCustomers() throws Exception {
        for (String username : List.of("T-IN", "T-OUT", "+919900000004")) {
            assertProblem(patchProfile(login(username), alias("Nope", 0)).andReturn(), 403, "forbidden", PATH);
        }
    }

    @Test
    void updateWithoutCsrfTokenIsForbidden() throws Exception {
        int version = version("CUST-T-0001");
        MvcResult result = mvc.perform(patch(PATH)
                        .cookie(login("+919900000001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(alias("NoCsrf", version))))
                .andReturn();

        assertProblem(result, 403, "forbidden", PATH);
        assertThat(alias("CUST-T-0001")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions patchProfile(Cookie session, Map<String, Object> body) throws Exception {
        return mvc.perform(patch(PATH)
                .cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> alias(String aliasName, int version) {
        Map<String, Object> body = new HashMap<>();
        body.put("aliasName", aliasName);
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> address(String line1, String city, String state, String postalCode) {
        Map<String, Object> address = new HashMap<>();
        address.put("line1", line1);
        address.put("line2", null);
        address.put("city", city);
        address.put("state", state);
        address.put("postalCode", postalCode);
        address.put("country", "IN");
        return address;
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("errors")).stream()
                .map(e -> (String) e.get("field"))
                .toList();
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private String alias(String customerRef) {
        return jdbc.queryForObject("SELECT alias_name FROM customers WHERE customer_ref = :ref",
                Map.of("ref", customerRef), String.class);
    }

    private int version(String customerRef) {
        return jdbc.queryForObject("SELECT version FROM customers WHERE customer_ref = :ref",
                Map.of("ref", customerRef), Integer.class);
    }

    private long customerId(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref",
                Map.of("ref", customerRef), Long.class);
    }

    private long auditCount(String action, String customerRef) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE action = :action AND entity_type = 'CUSTOMER' AND entity_id = :id
                """, Map.of("action", action, "id", String.valueOf(customerId(customerRef))), Long.class);
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.21." + (n / 250) + "." + (n % 250 + 1);
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
