package com.telecom.support.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code plans} and {@code plan_benefits} (design 02-customer backend §2, §6). */
@Repository
class PlanRepository {

    record PlanRow(long id, String code, String name, String planType, BigDecimal monthlyRental, String description,
                   String status) {}

    record BenefitRow(long planId, String benefitType, String label, String valueText, boolean key) {}

    private static final String PLAN_COLUMNS = """
            SELECT id, code, name, plan_type, monthly_rental, description, status
            FROM plans
            """;

    private static final String FIND_BY_IDS = PLAN_COLUMNS + """
            WHERE id IN (:ids)
            ORDER BY id
            """;

    private static final String FIND_BY_CODE = PLAN_COLUMNS + """
            WHERE code = :code
            """;

    private static final String FIND_ACTIVE = PLAN_COLUMNS + """
            WHERE status = 'ACTIVE'
            ORDER BY monthly_rental, code
            """;

    private static final String FIND_BENEFITS = """
            SELECT plan_id, benefit_type, label, value_text, is_key
            FROM plan_benefits
            WHERE plan_id IN (:planIds)
            ORDER BY plan_id, sort_order, id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PlanRow> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(FIND_BY_IDS, Map.of("ids", ids), (rs, i) -> plan(rs));
    }

    List<PlanRow> findByCode(String code) {
        return jdbc.query(FIND_BY_CODE, Map.of("code", code), (rs, i) -> plan(rs));
    }

    List<PlanRow> findActive() {
        return jdbc.query(FIND_ACTIVE, Map.of(), (rs, i) -> plan(rs));
    }

    /** Benefits of the given plans in display order ({@code sort_order}). */
    List<BenefitRow> findBenefits(Collection<Long> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(FIND_BENEFITS, Map.of("planIds", planIds), (rs, i) -> new BenefitRow(
                rs.getLong("plan_id"), rs.getString("benefit_type"), rs.getString("label"),
                rs.getString("value_text"), rs.getBoolean("is_key")));
    }

    private static PlanRow plan(ResultSet rs) throws SQLException {
        return new PlanRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("plan_type"),
                rs.getBigDecimal("monthly_rental"), rs.getString("description"), rs.getString("status"));
    }
}
