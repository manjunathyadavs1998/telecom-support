package com.telecom.support.servicecase;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * E10 {@code GET /api/v1/case-categories} (design 02-customer backend §4, §5, R10, §9; CUS-06.2, BR-07). Reference
 * data: V5 placeholder CUSTOMER categories (OQ 5). The test adds INTERNAL and inactive rows and removes them after.
 */
class CaseCategoryIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/case-categories";
    private static final String XSRF = "category-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    CategoryRepository categories;

    @Autowired
    SlaPolicyRepository slaPolicies;

    @AfterEach
    void removeTestCategories() {
        jdbc.update("DELETE FROM categories WHERE code LIKE 'T\\_%' AND parent_id IS NOT NULL", Map.of());
        jdbc.update("DELETE FROM categories WHERE code LIKE 'T\\_%'", Map.of());
    }

    @Test
    void listsActiveCustomerCategoriesWithTheirActiveSubcategoriesInOrder() throws Exception {
        MvcResult result = mvc.perform(get(PATH).cookie(login("+919900000001"))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        List<Map<String, Object>> body = json(result);
        assertThat(body).extracting(c -> c.get("code"))
                .containsExactly("BILLING", "NETWORK", "PLAN_SERVICES", "SIM_DEVICE", "OTHER");
        assertThat(body.get(0)).containsOnlyKeys("code", "name", "subcategories");
        assertThat(body.get(0).get("name")).isEqualTo("Billing and payments");
        assertThat(body.get(0).get("subcategories")).isEqualTo(List.of(
                Map.of("code", "BILL_DISPUTE", "name", "Dispute a charge"),
                Map.of("code", "BILL_COPY", "name", "Copy of a bill"),
                Map.of("code", "PAYMENT_MISSING", "name", "Payment not reflected")));
        assertThat(subcategoryCodes(body.get(1))).containsExactly("NO_SIGNAL", "SLOW_DATA", "CALL_DROPS");
        assertThat(subcategoryCodes(body.get(4))).isEmpty();
    }

    @Test
    void hidesInternalAndInactiveCategoriesAndTheirChildren() throws Exception {
        insertCategory("T_INTERNAL", null, "INTERNAL", true);
        insertCategory("T_INTERNAL_CHILD", "T_INTERNAL", "INTERNAL", true);
        insertCategory("T_INACTIVE", null, "CUSTOMER", false);
        insertCategory("T_INACTIVE_CHILD", "T_INACTIVE", "CUSTOMER", true);
        insertCategory("T_BILL_OLD", "BILLING", "CUSTOMER", false);
        insertCategory("T_BILL_INTERNAL", "BILLING", "INTERNAL", true);

        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000002")))
                .andExpect(status().isOk()).andReturn());

        assertThat(body).extracting(c -> c.get("code"))
                .containsExactly("BILLING", "NETWORK", "PLAN_SERVICES", "SIM_DEVICE", "OTHER");
        assertThat(subcategoryCodes(body.get(0))).containsExactly("BILL_DISPUTE", "BILL_COPY", "PAYMENT_MISSING");
    }

    @Test
    void everyListedLeafResolvesAnActiveSlaLeafFirstThenParent() {
        // BR-07, R10: the seed guarantees an SLA row for every leaf the customer can pick
        List<CategoryRow> rows = categories.findCustomerTree();
        Map<Long, CategoryRow> byId = rows.stream().collect(Collectors.toMap(CategoryRow::id, r -> r));
        Map<Long, Long> childCount = rows.stream().filter(r -> r.parentId() != null)
                .collect(Collectors.groupingBy(CategoryRow::parentId, Collectors.counting()));
        List<CategoryRow> leaves = rows.stream()
                .filter(r -> r.parentId() != null || !childCount.containsKey(r.id()))
                .toList();

        assertThat(leaves).hasSize(12);
        for (CategoryRow leaf : leaves) {
            CategoryRow parent = leaf.parentId() == null ? null : byId.get(leaf.parentId());
            String severity = leaf.defaultSeverity() != null ? leaf.defaultSeverity() : parent.defaultSeverity();
            Optional<SlaPolicyRepository.SlaPolicyRow> sla = slaPolicies.findActive(leaf.id(), severity)
                    .or(() -> parent == null ? Optional.empty() : slaPolicies.findActive(parent.id(), severity));
            assertThat(sla).as("SLA for %s/%s", leaf.code(), severity).isPresent();
            assertThat(sla.get().responseMinutes()).isPositive();
            assertThat(sla.get().resolutionMinutes()).isGreaterThan(sla.get().responseMinutes());
        }
        assertThat(slaPolicies.findActive(byId.values().stream()
                .filter(r -> r.code().equals("NO_SIGNAL")).findFirst().orElseThrow().id(), "CRITICAL"))
                .get().extracting(SlaPolicyRepository.SlaPolicyRow::responseMinutes).isEqualTo(30);
    }

    @Test
    void needsAuthenticationAndCaseCreate() throws Exception {
        assertProblem(mvc.perform(get(PATH)).andReturn(), 401, "unauthenticated", PATH);
        // INBOUND_AGENT holds case.create.onbehalf, not case.create (§8)
        for (String username : List.of("T-IN", "T-OUT", "T-BUS")) {
            assertProblem(mvc.perform(get(PATH).cookie(login(username))).andReturn(), 403, "forbidden", PATH);
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<String> subcategoryCodes(Map<String, Object> category) {
        return ((List<Map<String, Object>>) category.get("subcategories")).stream()
                .map(s -> (String) s.get("code")).toList();
    }

    private void insertCategory(String code, String parentCode, String audience, boolean active) {
        jdbc.update("""
                INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active,
                                        sort_order, created_at, updated_at)
                VALUES (:code, :code, (SELECT id FROM (SELECT id FROM categories WHERE code = :parent) p), :audience,
                        'REQUEST', 'LOW', :active, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("parent", parentCode)
                .addValue("audience", audience)
                .addValue("active", active));
    }

    private List<Map<String, Object>> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.25." + (n / 250) + "." + (n % 250 + 1);
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
