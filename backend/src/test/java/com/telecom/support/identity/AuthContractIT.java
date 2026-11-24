package com.telecom.support.identity;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Gap pass on 01 backend §4–§5, §8 (NFR-SEC): who may call {@code /me} and {@code /logout}, the exact {@code Retry-After}
 * values, ProblemDetail shape of the auth errors, cookie flags and secrets in logs. Time moves only through the mutable
 * clock; every test uses its own client IPs (the limiter keeps state for the whole context).
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthContractIT extends AbstractMySqlIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";
    private static final String CSRF = "/api/v1/auth/csrf";
    private static final String PRE = "gap-pre-login-token";
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-04-01T08:00:00Z"));
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    ObjectMapper objectMapper;

    static Clock fixedClock() {
        return CLOCK;
    }

    /** Every seeded role combination: /me and logout need only a session, never a permission (§5). */
    private static final List<String> EVERY_ROLE_USER = List.of(
            "+919900000001", "T-IN", "T-IN-REACT", "T-OUT", "T-OUT-SUP", "T-BUS", "T-BUS-AUD");

    @Test
    void meAndLogoutAreOpenToEveryRoleOnceSignedIn() throws Exception {
        for (String username : EVERY_ROLE_USER) {
            MvcResult login = login(nextIp(), username, TEST_PASSWORD);
            assertThat(login.getResponse().getStatus()).as("login " + username).isEqualTo(200);
            Cookie session = cookie(login, "SESSION");
            Cookie xsrf = cookie(login, "XSRF-TOKEN");
            mvc.perform(get(ME).cookie(session)).andExpect(status().isOk());
            mvc.perform(post(LOGOUT).cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                    .andExpect(status().isNoContent());
            mvc.perform(get(ME).cookie(session)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anonymousMeAndLogoutAre401ProblemsWithoutRedirect() throws Exception {
        MvcResult me = mvc.perform(get(ME)).andReturn();
        assertProblem(me, 401, "unauthenticated", ME);
        assertThat(me.getResponse().getHeader("Location")).isNull();

        // logout with a valid CSRF pair but no session: the chain answers 401, not 204/500
        MvcResult logout = mvc.perform(post(LOGOUT).cookie(new Cookie("XSRF-TOKEN", PRE)).header("X-XSRF-TOKEN", PRE))
                .andReturn();
        assertProblem(logout, 401, "unauthenticated", LOGOUT);
        assertThat(logout.getResponse().getHeader("Location")).isNull();

        // anonymous logout without CSRF is rejected by the CSRF check
        assertProblem(mvc.perform(post(LOGOUT)).andReturn(), 403, "forbidden", LOGOUT);
    }

    @Test
    void forgedOrUnknownSessionCookieIs401() throws Exception {
        assertProblem(mvc.perform(get(ME).cookie(new Cookie("SESSION", "bm90LWEtcmVhbC1zZXNzaW9u"))).andReturn(),
                401, "unauthenticated", ME);
    }

    @Test
    void l1RetryAfterIsTheExactWholeSecondsUntilATokenRefills() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 20; i++) {
            assertThat(login(ip, "gap-l1-" + i, "x").getResponse().getStatus()).isEqualTo(401);
        }
        // 20 per minute refills one token every 3 s, clock frozen
        MvcResult throttled = login(ip, "gap-l1-21", "x");
        assertProblem(throttled, 429, "too-many-requests", LOGIN);
        assertThat(throttled.getResponse().getHeader("Retry-After")).isEqualTo("3");

        CLOCK.set(CLOCK.instant().plusSeconds(1));
        assertThat(login(ip, "gap-l1-22", "x").getResponse().getHeader("Retry-After")).isEqualTo("2");

        CLOCK.set(CLOCK.instant().plusSeconds(2));
        assertThat(login(ip, "gap-l1-23", "x").getResponse().getStatus()).isEqualTo(401); // freed exactly on time
    }

    @Test
    void l2RetryAfterCountsDownFrom900Seconds() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            assertThat(login(ip, "T-OUT", "Wrong-password-1").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult first = login(ip, "T-OUT", TEST_PASSWORD);
        assertProblem(first, 429, "too-many-requests", LOGIN);
        assertThat(first.getResponse().getHeader("Retry-After")).isEqualTo("900");

        CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(5)));
        assertThat(login(ip, "T-OUT", TEST_PASSWORD).getResponse().getHeader("Retry-After")).isEqualTo("600");

        CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(10)).minusSeconds(1));
        assertThat(login(ip, "T-OUT", TEST_PASSWORD).getResponse().getHeader("Retry-After")).isEqualTo("1");

        CLOCK.set(CLOCK.instant().plusSeconds(1));
        assertThat(login(ip, "T-OUT", TEST_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void badCredentialsAndValidationOnLoginKeepTheProblemShape() throws Exception {
        assertProblem(login(nextIp(), "gap-nobody", TEST_PASSWORD), 401, "unauthenticated", LOGIN);
        MvcResult blank = mvc.perform(loginRequest(nextIp(), "").content("{\"username\":\"\",\"password\":\"\"}"))
                .andReturn();
        assertProblem(blank, 400, "validation", LOGIN);
        assertProblem(mvc.perform(loginRequest(nextIp(), "").content("{\"username\":")).andReturn(),
                400, "bad-request", LOGIN);
        assertProblem(mvc.perform(loginRequest(nextIp(), "").contentType(MediaType.TEXT_PLAIN).content("a=b"))
                .andReturn(), 415, "unsupported-media-type", LOGIN);
    }

    @Test
    void csrfBootstrapSetsTheCookieWithTheSameTokenAndReadableFlags() throws Exception {
        MvcResult result = mvc.perform(get(CSRF)).andExpect(status().isOk()).andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("headerName")).isEqualTo("X-XSRF-TOKEN");
        assertThat(rawCookie(result, "XSRF-TOKEN").getValue()).isEqualTo(body.get("token"));
        assertXsrfFlags(result, false);
    }

    @Test
    void cookieFlagsOnLoginAndLogoutOverPlainHttpAndHttps() throws Exception {
        MvcResult plain = login(nextIp(), "T-IN", TEST_PASSWORD);
        assertThat(setCookie(plain, "SESSION")).contains("HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Secure");
        assertXsrfFlags(plain, false);

        MvcResult secure = mvc.perform(loginRequest(nextIp(), "T-IN").secure(true)
                .content(body("T-IN", TEST_PASSWORD))).andReturn();
        assertThat(setCookie(secure, "SESSION")).contains("HttpOnly", "SameSite=Lax", "Secure");
        assertXsrfFlags(secure, true);

        Cookie session = cookie(secure, "SESSION");
        Cookie xsrf = cookie(secure, "XSRF-TOKEN");
        MvcResult logout = mvc.perform(post(LOGOUT).secure(true).cookie(session, xsrf)
                .header("X-XSRF-TOKEN", xsrf.getValue())).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertXsrfFlags(logout, true);
    }

    @Test
    void logsNeverContainThePasswordOrTheFullUsernameEvenWhenThrottled(CapturedOutput output) throws Exception {
        int start = output.getAll().length();
        String ip = nextIp();
        String wrong = "Wr0ng-Secret-Pw-9";
        String unknown = "gap-probe-user-9";
        String known = "T-IN-REACT";
        assertThat(login(ip, known, wrong).getResponse().getStatus()).isEqualTo(401);
        for (int i = 0; i < 5; i++) { // the 5th failure blocks the pair, the 6th is a logged 429
            login(ip, unknown, wrong);
        }
        assertThat(login(ip, unknown, wrong).getResponse().getStatus()).isEqualTo(429);

        String logs = output.getAll().substring(start);
        assertThat(logs).contains("Login throttled").doesNotContain(wrong, unknown, known, TEST_PASSWORD);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static String nextIp() {
        int n = IPS.incrementAndGet();
        return "10.9." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String body(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    /** What the SPA sends: the XSRF-TOKEN cookie echoed in the header; callers set the body. */
    private static MockHttpServletRequestBuilder loginRequest(String ip, String username) {
        return post(LOGIN)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .cookie(new Cookie("XSRF-TOKEN", PRE))
                .header("X-XSRF-TOKEN", PRE)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MvcResult login(String ip, String username, String password) throws Exception {
        return mvc.perform(loginRequest(ip, username).content(body(username, password))).andReturn();
    }

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

    private static Cookie rawCookie(MvcResult result, String name) {
        Cookie found = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals(name)) {
                found = c;
            }
        }
        assertThat(found).as("cookie " + name).isNotNull();
        return found;
    }

    /** XSRF-TOKEN is written through the servlet Cookie API: readable by the SPA, SameSite=Lax, site-wide path. */
    private static void assertXsrfFlags(MvcResult result, boolean secure) {
        Cookie c = rawCookie(result, "XSRF-TOKEN");
        assertThat(c.isHttpOnly()).as("XSRF-TOKEN must be readable by the SPA").isFalse();
        assertThat(c.getAttribute("SameSite")).as("XSRF-TOKEN SameSite").isEqualTo("Lax");
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getSecure()).isEqualTo(secure);
    }

    private static String setCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
    }
}
