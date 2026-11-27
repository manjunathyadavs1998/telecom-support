package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.identity.CurrentUser.UserType;
import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.PermissionProbeController;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The permission matrix end to end (design 01-foundation backend §3, §3.1, §6, §8; BRD §8, BR-10, XC-02). The expected
 * matrix is written out here from §3, independently of V2. B7: loaded authorities and seed data. B9: {@code /me} and a
 * {@code @PreAuthorize} probe per {@link Permissions} constant (200 for holders, 403 for every other role, 401 anonymous).
 * Probe cases are generated from the constants, so a new permission cannot be left out.
 */
@Import(PermissionProbeController.class)
class PermissionMatrixIT extends AbstractMySqlIT {

    private static final String XSRF = "matrix-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    /** Design §3, one column per role. */
    private static final Map<String, Set<String>> MATRIX = Map.of(
            "CUSTOMER", Set.of(
                    "customer.profile.read", "customer.profile.update", "plan.read", "plan.change", "promotion.read",
                    "promotion.claim", "case.create", "case.read", "payment.read", "payment.initiate"),
            "INBOUND_AGENT", Set.of(
                    "customer.profile.read", "customer.lookup", "customer.verify", "plan.read", "promotion.read",
                    "case.create.onbehalf", "case.category.internal", "case.severity.override", "case.read",
                    "case.note.internal", "payment.read"),
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

    @Autowired
    ObjectMapper objectMapper;

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

        assertThat(constants).hasSize(26);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("testUsers")
    void meReturnsExactlyTheMatrixPermissions(String username, UserType type, Set<String> roles) throws Exception {
        Cookie session = login(username);

        MvcResult me = mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk()).andReturn();
        Object permissions = objectMapper.readValue(me.getResponse().getContentAsString(), Map.class)
                .get("permissions");
        assertThat(permissions).isInstanceOf(List.class);
        assertThat((List<?>) permissions).doesNotHaveDuplicates();
        assertThat(new HashSet<>((List<?>) permissions)).isEqualTo(expectedPermissions(roles));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testUsers")
    void probeIs200ForHoldersAnd403ForEveryoneElse(String username, UserType type, Set<String> roles)
            throws Exception {
        Cookie session = login(username);
        Set<String> held = expectedPermissions(roles);

        Map<String, Integer> expected = new TreeMap<>();
        Map<String, Integer> actual = new TreeMap<>();
        for (String permission : permissionConstants()) {
            expected.put(permission, held.contains(permission) ? 200 : 403);
            actual.put(permission, mvc.perform(get(PermissionProbeController.PATH + "/" + permission).cookie(session))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void probeIs401ForAnonymous() throws Exception {
        Map<String, Integer> actual = new TreeMap<>();
        for (String permission : permissionConstants()) {
            actual.put(permission, mvc.perform(get(PermissionProbeController.PATH + "/" + permission))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(actual).hasSize(permissionConstants().size());
        assertThat(actual.values()).containsOnly(401);
    }

    @Test
    void everyPermissionHasExactlyOneProbeGuardedByItsOwnAuthority() {
        Map<String, String> guards = new TreeMap<>();
        for (Method m : PermissionProbeController.class.getDeclaredMethods()) {
            GetMapping mapping = m.getAnnotation(GetMapping.class);
            if (mapping == null) {
                continue;
            }
            PreAuthorize guard = m.getAnnotation(PreAuthorize.class);
            assertThat(guard).as(m.getName() + " has @PreAuthorize").isNotNull();
            assertThat(guards.put(mapping.value()[0].substring(1), guard.value())).as("duplicate probe").isNull();
        }
        Map<String, String> expected = new TreeMap<>();
        for (Field f : permissionFields()) {
            expected.put(value(f), "hasAuthority(T(" + Permissions.class.getName() + ")." + f.getName() + ")");
        }
        assertThat(guards).isEqualTo(expected);
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

    private static Set<String> expectedPermissions(Set<String> roles) {
        return roles.stream().flatMap(r -> MATRIX.get(r).stream()).collect(Collectors.toSet());
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.9." + (n / 250) + "." + (n % 250 + 1);
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
        Cookie session = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("SESSION")) {
                session = new Cookie("SESSION", c.getValue());
            }
        }
        assertThat(session).as("session cookie").isNotNull();
        return session;
    }

    private static Set<String> authorities(CurrentUser user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> castRoles(Object roles) {
        return (Set<String>) roles;
    }

    private static Set<String> permissionConstants() {
        return permissionFields().stream().map(PermissionMatrixIT::value).collect(Collectors.toSet());
    }

    private static List<Field> permissionFields() {
        return Arrays.stream(Permissions.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                        && f.getType() == String.class)
                .toList();
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The slice's openapi contract, found by walking up from the working directory (so the test runs from
     * {@code backend/}, the repo root or an IDE run configuration).
     */
    static Path openapiFile() {
        Path relative = Path.of("docs", "design", "01-foundation", "openapi.yaml");
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Cannot find " + relative + " in " + start + " or any parent directory");
    }

    /** Items of {@code components.schemas.Permission.enum} in the slice's openapi contract. */
    private static List<String> openapiPermissionEnum() throws IOException {
        List<String> lines = Files.readAllLines(openapiFile());
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
