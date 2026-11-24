package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * B8: login / logout / me, throttling L1–L3, CSRF rotation and session policy (01 backend §4–§4.3, §5, §7, §8; NFR-SEC).
 * The limiter keeps state for the whole context, so every test uses its own client IPs and usernames. Time moves only
 * through the {@link MutableClock}; nothing sleeps.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthIT extends AbstractMySqlIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";
    private static final String TYPE = "https://telecom.example/problems/";
    private static final String PRE_LOGIN_TOKEN = "pre-login-token";
    private static final String DISABLED_USER = "T-AUTH-OFF";
    private static final String MID_SESSION_USER = "T-AUTH-MID";
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-03-01T08:00:00Z"));
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AuthController authController;

    @Autowired
    ObjectMapper objectMapper;

    static Clock fixedClock() {
        return CLOCK;
    }

    @BeforeEach
    void dedicatedUsers() {
        for (String[] u : new String[][] {{DISABLED_USER, "DISABLED"}, {MID_SESSION_USER, "ACTIVE"}}) {
            jdbc.update("""
                    INSERT IGNORE INTO users (username, password_hash, display_name, user_type, status, created_at, updated_at)
                    SELECT :username, password_hash, 'Auth Test User', 'STAFF', :status, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                    FROM users WHERE username = 'T-IN'
                    """, Map.of("username", u[0], "status", u[1]));
            jdbc.update("UPDATE users SET status = :status WHERE username = :username",
                    Map.of("username", u[0], "status", u[1]));
        }
    }

    @Test
    void loginReturnsMeAndTheSessionSurvivesFurtherRequests() throws Exception {
        MvcResult result = login(nextIp(), "T-IN", TEST_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("T-IN"))
                .andExpect(jsonPath("$.displayName").value("Test Inbound Agent"))
                .andExpect(jsonPath("$.userType").value("STAFF"))
                .andExpect(jsonPath("$.teamId").isNumber())
                .andExpect(jsonPath("$.customerId").isEmpty())
                .andReturn();
        Map<String, Object> me = json(result);
        assertThat(me.get("roles")).isEqualTo(List.of("INBOUND_AGENT"));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username = 'T-IN'", Map.of(), Long.class);
        assertThat(((Number) me.get("id")).longValue()).isEqualTo(id);
        assertThat(me.get("permissions")).isEqualTo(jdbc.queryForList("""
                SELECT DISTINCT p.code FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE ur.user_id = :id ORDER BY p.code
                """, Map.of("id", id), String.class));
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = :id", Map.of("id", id),
                java.time.LocalDateTime.class)).isNotNull();
        assertThat(auditRows(result, "LOGIN_SUCCESS")).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("actor_user_id")).longValue()).isEqualTo(id));

        Cookie session = cookie(result, "SESSION");
        for (int i = 0; i < 2; i++) { // the serialized CurrentUser is read back from Spring Session JDBC
            mvc.perform(get(ME).cookie(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("T-IN"));
        }
    }

    @Test
    void unknownUserBadPasswordAndDisabledGetIdentical401s() throws Exception {
        String ip = nextIp();
        MvcResult unknown = login(ip, "nobody-at-all", TEST_PASSWORD).andReturn();
        MvcResult badPassword = login(ip, "T-IN", "Wrong-password-1").andReturn();
        MvcResult disabled = login(ip, DISABLED_USER, TEST_PASSWORD).andReturn();

        Map<String, Object> expected = withoutCorrelationId(unknown);
        assertThat(expected).containsEntry("status", 401).containsEntry("type", TYPE + "unauthenticated")
                .containsEntry("detail", "Invalid username or password.");
        for (MvcResult r : List.of(unknown, badPassword, disabled)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getHeader("Location")).isNull();
            assertThat(withoutCorrelationId(r)).isEqualTo(expected);
        }

        assertFailureRow(unknown, "unknown_user", "nob********ll");
        assertFailureRow(badPassword, "bad_password", "***");
        assertFailureRow(disabled, "disabled", "T-A*****FF");
    }

    @Test
    void l1TwentyAttemptsPerMinutePerIpThen429WithRetryAfter() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 20; i++) {
            login(ip, "l1-user-" + i, "x").andExpect(status().isUnauthorized());
        }
        MvcResult throttled = login(ip, "l1-user-21", "x")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(TYPE + "too-many-requests"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();
        assertThat(Long.parseLong(throttled.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(auditRows(throttled, "LOGIN_FAILURE")).isEmpty(); // 429s are logged, not audited

        login(nextIp(), "l1-user-21", "x").andExpect(status().isUnauthorized()); // other IPs unaffected
    }

    @Test
    void l2FiveFailuresBlockThePairFor15MinutesOnly() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            login(ip, "T-IN-REACT", "Wrong-password-1").andExpect(status().isUnauthorized());
        }
        login(ip, "T-IN-REACT", TEST_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        login(ip, "t-in-react ", TEST_PASSWORD).andExpect(status().isTooManyRequests()); // normalised username

        login(nextIp(), "T-IN-REACT", TEST_PASSWORD).andExpect(status().isOk()); // same user, other IP

        CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(15)));
        login(ip, "T-IN-REACT", TEST_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void successfulLoginClearsThePairCounter() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            login(ip, "T-BUS", "Wrong-password-1").andExpect(status().isUnauthorized());
        }
        login(ip, "T-BUS", TEST_PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login(ip, "T-BUS", "Wrong-password-1").andExpect(status().isUnauthorized());
        }
        login(ip, "T-BUS", TEST_PASSWORD).andExpect(status().isOk()); // 8 failures in total, never blocked
    }

    @Test
    void l3AnomalyOnThe51stFailureInAnHourWithoutLockingTheAccount() throws Exception {
        List<MvcResult> failures = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String ip = nextIp();
            for (int j = 0; j < 5 && failures.size() < 52; j++) {
                failures.add(login(ip, "T-OUT", "Wrong-password-1").andExpect(status().isUnauthorized()).andReturn());
            }
        }
        assertThat(auditRows(failures.get(49), "LOGIN_ANOMALY")).isEmpty();
        assertThat(auditRows(failures.get(50), "LOGIN_ANOMALY")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("outcome")).isEqualTo("FAILURE");
            assertThat(row.get("details")).asString()
                    .contains("\"username\": \"***\"", "\"failures\": 51", "\"windowMinutes\": 60");
        });
        assertThat(auditRows(failures.get(51), "LOGIN_ANOMALY")).isEmpty(); // once per window

        login(nextIp(), "T-OUT", TEST_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void loginChangesTheSessionId() throws Exception {
        Cookie first = cookie(login(nextIp(), "T-OUT-SUP", TEST_PASSWORD).andExpect(status().isOk()).andReturn(),
                "SESSION");
        Cookie second = cookie(mvc.perform(loginRequest(nextIp(), "T-OUT-SUP", TEST_PASSWORD).cookie(first))
                .andExpect(status().isOk()).andReturn(), "SESSION");

        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mvc.perform(get(ME).cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get(ME).cookie(second)).andExpect(status().isOk());
    }

    @Test
    void sessionCookieIsHttpOnlySameSiteLaxAndSecureOverHttps() throws Exception {
        String plain = setCookie(login(nextIp(), "T-IN", TEST_PASSWORD).andExpect(status().isOk()).andReturn(),
                "SESSION");
        assertThat(plain).contains("HttpOnly", "SameSite=Lax").doesNotContain("Secure");

        String secure = setCookie(mvc.perform(loginRequest(nextIp(), "T-IN", TEST_PASSWORD).secure(true))
                .andExpect(status().isOk()).andReturn(), "SESSION");
        assertThat(secure).contains("HttpOnly", "SameSite=Lax", "Secure");
    }

    @Test
    void meAndLogoutAreGuardedByMethodSecurityToo() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> authController.me())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> authController.logout(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void apiWithoutSessionIs401WithoutRedirect() throws Exception {
        mvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(TYPE + "unauthenticated"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void loginRotatesCsrfTokenAndLogoutNeedsTheRotatedOne() throws Exception {
        MvcResult login = login(nextIp(), "T-BUS-AUD", TEST_PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie session = cookie(login, "SESSION");
        Cookie rotated = cookie(login, "XSRF-TOKEN");
        assertThat(rotated.getValue()).isNotBlank().isNotEqualTo(PRE_LOGIN_TOKEN);

        // the browser now holds the rotated cookie; a stale header from before login is rejected
        mvc.perform(post(LOGOUT).cookie(session, rotated).header("X-XSRF-TOKEN", PRE_LOGIN_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(post(LOGOUT).cookie(session)).andExpect(status().isForbidden()); // no CSRF at all

        MvcResult logout = mvc.perform(post(LOGOUT).cookie(session, rotated)
                        .header("X-XSRF-TOKEN", rotated.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(cookie(logout, "XSRF-TOKEN").getValue()).isNotBlank().isNotEqualTo(rotated.getValue());
        assertThat(auditRows(logout, "LOGOUT")).singleElement()
                .satisfies(row -> assertThat(row.get("actor_username")).isEqualTo("T-BUS-AUD"));

        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfIs403() throws Exception {
        mvc.perform(post(LOGIN).with(r -> { r.setRemoteAddr(nextIp()); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(body("T-IN", TEST_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE + "forbidden"));
    }

    @Test
    void idleSessionExpires() throws Exception {
        Cookie session = cookie(login(nextIp(), "T-IN", TEST_PASSWORD).andReturn(), "SESSION");
        mvc.perform(get(ME).cookie(session)).andExpect(status().isOk());

        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = LAST_ACCESS_TIME - :ms WHERE SESSION_ID = :id",
                Map.of("ms", Duration.ofMinutes(31).toMillis(), "id", sessionId(session)));
        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionExpiresAfterTheAbsoluteLifetime() throws Exception {
        Cookie session = cookie(login(nextIp(), "T-IN", TEST_PASSWORD).andReturn(), "SESSION");
        CLOCK.set(CLOCK.instant().plus(Duration.ofHours(12)).minusSeconds(1));
        mvc.perform(get(ME).cookie(session)).andExpect(status().isOk());

        CLOCK.set(CLOCK.instant().plusSeconds(1));
        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized()); // invalidated, not just denied
    }

    @Test
    void userDisabledMidSessionGets401() throws Exception {
        Cookie session = cookie(login(nextIp(), MID_SESSION_USER, TEST_PASSWORD).andReturn(), "SESSION");
        mvc.perform(get(ME).cookie(session)).andExpect(status().isOk());

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE username = :u", Map.of("u", MID_SESSION_USER));
        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE username = :u", Map.of("u", MID_SESSION_USER));
        mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized()); // the session is gone
    }

    @Test
    void logsContainNoPasswordOrSessionId(CapturedOutput output) throws Exception {
        int start = output.getAll().length(); // only this test's output (the capture spans the class)
        String ip = nextIp();
        String wrong = "Never-Log-Me-42";
        login(ip, "T-IN-REACT", wrong).andExpect(status().isUnauthorized());
        MvcResult ok = login(ip, "T-IN-REACT", TEST_PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie session = cookie(ok, "SESSION");
        Cookie xsrf = cookie(ok, "XSRF-TOKEN");
        mvc.perform(get(ME).cookie(session)).andExpect(status().isOk());
        mvc.perform(post(LOGOUT).cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());

        assertThat(output.getAll().substring(start)).doesNotContain(TEST_PASSWORD, wrong, session.getValue(), sessionId(session));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static String nextIp() {
        int n = IPS.incrementAndGet();
        return "10.8." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String body(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    /** What the SPA sends: the bootstrap XSRF-TOKEN cookie echoed in the header (not spring-security-test's csrf()). */
    private static MockHttpServletRequestBuilder loginRequest(String ip, String username, String password) {
        return post(LOGIN)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .cookie(new Cookie("XSRF-TOKEN", PRE_LOGIN_TOKEN))
                .header("X-XSRF-TOKEN", PRE_LOGIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(username, password));
    }

    private org.springframework.test.web.servlet.ResultActions login(String ip, String username, String password)
            throws Exception {
        return mvc.perform(loginRequest(ip, username, password));
    }

    /** The last cookie of that name on the response (the one a browser keeps). */
    private static Cookie cookie(MvcResult result, String name) {
        Cookie found = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals(name)) {
                found = c;
            }
        }
        assertThat(found).as("cookie " + name).isNotNull();
        return new Cookie(name, found.getValue());
    }

    private static String setCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    /** Spring Session's cookie carries the Base64 of the {@code SESSION_ID}. */
    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private Map<String, Object> withoutCorrelationId(MvcResult result) throws Exception {
        Map<String, Object> body = json(result);
        assertThat(body.remove("correlationId")).isNotNull();
        return body;
    }

    private List<Map<String, Object>> auditRows(MvcResult result, String action) {
        return jdbc.queryForList("""
                SELECT actor_user_id, actor_username, outcome, entity_type, CAST(details AS CHAR) AS details
                FROM audit_events WHERE correlation_id = :cid AND action = :action
                """, Map.of("cid", result.getResponse().getHeader("X-Correlation-Id"), "action", action));
    }

    private void assertFailureRow(MvcResult result, String reason, String maskedUsername) {
        assertThat(auditRows(result, "LOGIN_FAILURE")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("actor_username")).isNull();
            assertThat(row.get("outcome")).isEqualTo("FAILURE");
            assertThat(row.get("details")).asString()
                    .contains("\"reason\": \"" + reason + "\"", "\"username\": \"" + maskedUsername + "\"");
        });
    }
}
