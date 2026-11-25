package com.telecom.support.servicecase;

import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/case-categories} — E10 (design 02-customer backend §4, §5, R10; CUS-06.2, BR-07): the active
 * customer-facing categories with their active subcategories, in display order. Needs {@code case.create} and a linked
 * customer (erratum E2), so INBOUND_AGENT (it holds {@code case.create.onbehalf} only) and an unlinked CUSTOMER get 403.
 */
@RestController
public class CaseCategoryController {

    /** A subcategory in the {@code Category} schema. */
    public record Subcategory(String code, String name) {}

    /** The {@code Category} schema; {@code subcategories} empty = the top level itself is the leaf (R10). */
    public record Category(String code, String name, List<Subcategory> subcategories) {}

    private final CategoryRepository categories;

    CaseCategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping("/api/v1/case-categories")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE)"
            + " and @customerScope.isCustomer()")
    @Transactional(readOnly = true)
    public List<Category> listCaseCategories() {
        Map<Long, CategoryRow> topLevels = new LinkedHashMap<>();
        Map<Long, List<Subcategory>> children = new LinkedHashMap<>();
        for (CategoryRow row : categories.findCustomerTree()) {
            if (row.parentId() == null) {
                topLevels.put(row.id(), row);
                children.put(row.id(), new ArrayList<>());
            } else {
                children.get(row.parentId()).add(new Subcategory(row.code(), row.name()));
            }
        }
        return topLevels.values().stream()
                .map(top -> new Category(top.code(), top.name(), List.copyOf(children.get(top.id()))))
                .toList();
    }
}
