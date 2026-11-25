package com.telecom.support.servicecase;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read side of {@code categories} (design 02-customer backend §2, R10; CUS-06.2). Two levels: a top level
 * ({@code parent_id} null) and its subcategories. Categories change only by migration in 02.
 */
@Repository
class CategoryRepository {

    record CategoryRow(long id, String code, String name, Long parentId, String caseType, String defaultSeverity) {}

    /**
     * Active CUSTOMER top-level categories and their active CUSTOMER subcategories (subcategories of an inactive or
     * INTERNAL parent are excluded), top levels first, each level by {@code sort_order}.
     */
    private static final String FIND_CUSTOMER_TREE = """
            SELECT c.id, c.code, c.name, c.parent_id, c.case_type, c.default_severity
            FROM categories c
            LEFT JOIN categories p ON p.id = c.parent_id
            WHERE c.active = TRUE AND c.audience = 'CUSTOMER'
              AND (c.parent_id IS NULL
                   OR (p.parent_id IS NULL AND p.active = TRUE AND p.audience = 'CUSTOMER'))
            ORDER BY c.parent_id IS NOT NULL, c.sort_order, c.code
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CategoryRow> findCustomerTree() {
        return jdbc.query(FIND_CUSTOMER_TREE, Map.of(), (rs, i) -> new CategoryRow(rs.getLong("id"),
                rs.getString("code"), rs.getString("name"), rs.getObject("parent_id", Long.class),
                rs.getString("case_type"), rs.getString("default_severity")));
    }
}
