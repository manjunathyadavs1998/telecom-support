package com.telecom.support.security;

import static com.telecom.support.security.Permissions.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.AbstractWebIntegrationTest;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Executable version of the BRD §8 permission matrix. Any added, removed or mis-mapped grant fails the build
 * (rbac-permission skill, BR-10). Expectations are written from the BRD, not read from the database.
 */
class PermissionMatrixIT extends AbstractWebIntegrationTest {

    private static final Set<String> CUSTOMER = Set.of(CUSTOMER_PROFILE_READ, CUSTOMER_PROFILE_UPDATE, PLAN_READ, PLAN_CHANGE,
            PROMOTION_READ, PROMOTION_CLAIM, CASE_CREATE, CASE_READ, PAYMENT_READ, PAYMENT_INITIATE);

    private static final Set<String> INBOUND = Set.of(CUSTOMER_LOOKUP, CUSTOMER_VERIFY, CUSTOMER_PROFILE_READ, PLAN_READ,
            PROMOTION_READ, CASE_CREATE_ON_BEHALF, CASE_CATEGORY_INTERNAL, CASE_READ, CASE_NOTE_INTERNAL, PAYMENT_READ);

    private static final Set<String> OUTBOUND = Set.of(CASE_READ, CASE_READ_QUEUE, CASE_ASSIGN, CASE_STATUS_UPDATE,
            CASE_NOTE_INTERNAL, CASE_BOOKMARK, ANALYTICS_READ);

    private static final Set<String> BUSINESS = Set.of(ANALYTICS_READ, AUDIT_READ, REPORTS_EXPORT);

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of("customer1", CUSTOMER),
                Arguments.of("inbound2", INBOUND),
                Arguments.of("inbound1", union(INBOUND, Set.of(SERVICE_REACTIVATE))),
                Arguments.of("outbound1", OUTBOUND),
                Arguments.of("supervisor1", union(OUTBOUND, Set.of(CASE_ASSIGN_ANY))),
                Arguments.of("business1", BUSINESS),
                Arguments.of("auditor1", union(BUSINESS, Set.of(REPORTS_EXPORT_PII))));
    }

    @ParameterizedTest(name = "{0} has exactly the BRD §8 permissions")
    @MethodSource("matrix")
    void roleHasExactlyTheExpectedPermissions(String username, Set<String> expected) {
        assertThat(appUser(username).permissions()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @ParameterizedTest(name = "BRD §8 negative cell: {0} must not have {1}")
    @MethodSource("forbiddenCells")
    void forbiddenCellsAreNotGranted(String username, String permission) {
        assertThat(appUser(username).permissions()).doesNotContain(permission);
    }

    /** The "No" cells of BRD §8 that carry the most risk, listed explicitly for readability. */
    static Stream<Arguments> forbiddenCells() {
        return Stream.of(
                Arguments.of("inbound1", CUSTOMER_PROFILE_UPDATE),   // only the customer edits contact fields
                Arguments.of("outbound1", CUSTOMER_PROFILE_UPDATE),
                Arguments.of("customer1", CASE_ASSIGN),
                Arguments.of("customer1", CASE_STATUS_UPDATE),
                Arguments.of("inbound1", CASE_BOOKMARK),             // bookmarks are outbound-only
                Arguments.of("outbound1", SERVICE_REACTIVATE),       // reactivation is inbound special permission
                Arguments.of("inbound2", SERVICE_REACTIVATE),
                Arguments.of("outbound1", PAYMENT_INITIATE),
                Arguments.of("business1", CASE_CREATE),
                Arguments.of("business1", REPORTS_EXPORT_PII),       // PII export needs explicit permission
                Arguments.of("outbound1", CASE_ASSIGN_ANY));         // reassignment is supervisor-only
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return Stream.concat(a.stream(), b.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
