package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.identity.CurrentUser.UserType;
import com.telecom.support.support.AbstractMySqlIT;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/**
 * B7 data level of the permission matrix (design 01-foundation backend §3, §3.1, §6; BRD §8, BR-10). The expected
 * matrix is written out here from §3, independently of V2. B9 extends this class with the {@code /me} and probe cases.
 */
class PermissionMatrixIT extends AbstractMySqlIT {

    /** Design §3, one column per role. */
    private static final Map<String, Set<String>> MATRIX = Map.of(
            "CUSTOMER", Set.of(
                    "customer.profile.read", "customer.profile.update", "plan.read", "plan.change", "promotion.read",
                    "promotion.claim", "case.create", "case.read", "payment.read", "payment.initiate"),
            "INBOUND_AGENT", Set.of(
                    "customer.profile.read", "customer.lookup", "customer.verify", "plan.read", "promotion.read",
                    "case.create.onbehalf", "case.category.internal", "case.read", "case.note.internal",
                    "payment.read"),
            "REACTIVATION_APPROVER", Set.of("service.reactivate"),
            "OUTBOUND_AGENT", Set.of(
                    "case.read", "case.read.queue", "case.assign", "case.status.update", "case.note.internal",
                    "case.bookmark", "analytics.read"),
            "OUTBOUND_SUPERVISOR", Set.of("case.assign.any"),
            "BUSINESS_ANALYST", Set.of("analytics.read", "audit.read", "reports.export"),
            "BUSINESS_AUDITOR", Set.of("reports.export.pii"));

    @Autowired
    AppUserDetailsService userDetailsService;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /** Test-data users (R__test_users.sql), one per §3.1 role combination. */
    static List<Arguments> testUsers() {
        return List.of(
                Arguments.of("+919900000001", UserType.CUSTOMER, Set.of("CUSTOMER")),
                Arguments.of("T-IN", UserType.STAFF, Set.of("INBOUND_AGENT")),
                Arguments.of("T-IN-REACT", UserType.STAFF, Set.of("INBOUND_AGENT", "REACTIVATION_APPROVER")),
                Arguments.of("T-OUT", UserType.STAFF, Set.of("OUTBOUND_AGENT")),
                Arguments.of("T-OUT-SUP", UserType.STAFF, Set.of("OUTBOUND_AGENT", "OUTBOUND_SUPERVISOR")),
                Arguments.of("T-BUS", UserType.STAFF, Set.of("BUSINESS_ANALYST")),
                Arguments.of("T-BUS-AUD", UserType.STAFF, Set.of("BUSINESS_ANALYST", "BUSINESS_AUDITOR")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testUsers")
    void loadedAuthoritiesEqualTheMatrixForTheRoleCombination(String username, UserType type, Set<String> roles) {
        CurrentUser user = userDetailsService.loadUserByUsername(username);

        Set<String> expected = roles.stream().flatMap(r -> MATRIX.get(r).stream()).collect(Collectors.toSet());
        assertThat(authorities(user)).isEqualTo(expected);
        assertThat(user.permissions()).isEqualTo(expected);
        assertThat(user.roles()).isEqualTo(roles);
        assertThat(user.userType()).isEqualTo(type);
        assertThat(user.getUsername()).isEqualTo(username);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPassword()).startsWith("$2");
        if (type == UserType.CUSTOMER) {
            assertThat(user.teamId()).isNull();
        } else {
            assertThat(user.teamId()).isNotNull();
        }
    }

    @Test
    void testDataCoversEveryRole() {
        Set<String> covered = new HashSet<>();
        testUsers().forEach(a -> covered.addAll(castRoles(a.get()[2])));
        assertThat(covered).isEqualTo(MATRIX.keySet());
        assertThat(jdbc.queryForList("SELECT code FROM roles", Map.of(), String.class))
                .containsExactlyInAnyOrderElementsOf(MATRIX.keySet());
    }

    @Test
    void permissionConstantsEqualTheSeededCodesAndTheOpenapiEnum() throws IOException {
        Set<String> constants = permissionConstants();
        List<String> seeded = jdbc.queryForList("SELECT code FROM permissions", Map.of(), String.class);

        assertThat(constants).hasSize(25);
        assertThat(seeded).containsExactlyInAnyOrderElementsOf(constants);
        assertThat(openapiPermissionEnum()).containsExactlyInAnyOrderElementsOf(constants);
        assertThat(MATRIX.values().stream().flatMap(Set::stream).collect(Collectors.toSet())).isEqualTo(constants);
    }

    @Test
    void seededRolePermissionsEqualTheMatrix() {
        for (Map.Entry<String, Set<String>> role : MATRIX.entrySet()) {
            List<String> seeded = jdbc.queryForList("""
                    SELECT p.code
                    FROM roles r
                    JOIN role_permissions rp ON rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.code = :role
                    """, Map.of("role", role.getKey()), String.class);
            assertThat(seeded).as(role.getKey()).containsExactlyInAnyOrderElementsOf(role.getValue());
        }
    }

    @Test
    void unknownUsernameIsNotFound() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("NO-SUCH-USER"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @Transactional // rolled back after the test
    void disabledUserLoadsAsNotEnabled() {
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = 'T-BUS'", Map.of());

        assertThat(userDetailsService.loadUserByUsername("T-BUS").isEnabled()).isFalse();
    }

    @Test
    void erasedCredentialsLeaveNoPasswordHash() {
        CurrentUser user = userDetailsService.loadUserByUsername("T-IN");
        user.eraseCredentials();

        assertThat(user.getPassword()).isNull();
        assertThat(user.toString()).doesNotContain("$2");
    }

    private static Set<String> authorities(CurrentUser user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> castRoles(Object roles) {
        return (Set<String>) roles;
    }

    private static Set<String> permissionConstants() {
        return Arrays.stream(Permissions.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                        && f.getType() == String.class)
                .map(PermissionMatrixIT::value)
                .collect(Collectors.toSet());
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Items of {@code components.schemas.Permission.enum} in the slice's openapi contract. */
    private static List<String> openapiPermissionEnum() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("..", "docs", "design", "01-foundation", "openapi.yaml"));
        int start = lines.indexOf("    Permission:");
        assertThat(start).as("Permission schema in openapi.yaml").isNotNegative();
        return lines.subList(start + 1, lines.size()).stream()
                .dropWhile(l -> !l.trim().equals("enum:"))
                .skip(1)
                .takeWhile(l -> l.trim().startsWith("- "))
                .map(l -> l.trim().substring(2).trim())
                .toList();
    }
}
