package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.StubProdAdapters;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * C1: under the {@code prod} profile the {@code SESSION} and {@code XSRF-TOKEN} cookies are Secure even on a plain-HTTP
 * request (01 backend §4.3, §8 "Secure under prod"; NFR-SEC). The default-profile behaviour is in {@code AuthIT}.
 */
@ActiveProfiles("prod")
// the values ProfileGuard requires under prod (review C1, C18, erratum E4); prod refuses disabled ("-") reconcile
// crons, so the jobs get a valid schedule that never fires during the test (1 Jan, 00:00)
@TestPropertySource(properties = {"app.customer.otp.hmac-secret=it-otp-secret",
        "app.payment.gateway.webhook-secret=it-webhook-secret", "app.payment.return-url-base=https://portal.example",
        "app.payment.reconcile.pending-cron=0 0 0 1 1 *", "app.payment.reconcile.late-capture-cron=0 0 0 1 1 *",
        // 03 backend §6, §8: prod refuses the in-memory mocks and disabled 03 jobs, and needs non-mock adapters
        "app.notification.mock.enabled=false", "app.reactivation.provisioning.mock.enabled=false",
        "app.notification.dispatch-cron=0 0 0 1 1 *", "app.reactivation.reconcile-cron=0 0 0 1 1 *",
        "app.reactivation.expiry-cron=0 0 0 1 1 *", "test.stub-adapters=all"})
@Import(StubProdAdapters.class)
class ProdCookieIT extends AbstractMySqlIT {

    private static final String TOKEN = "prod-xsrf-token";

    @Test
    void sessionAndCsrfCookiesAreSecureOverPlainHttp() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
                            r.setRemoteAddr("10.77.0.1");
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", TOKEN))
                        .header("X-XSRF-TOKEN", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"T-IN\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(login.getRequest().isSecure()).isFalse();

        assertThat(setCookie(login, "SESSION")).contains("HttpOnly", "SameSite=Lax", "Secure");
        assertThat(setCookie(login, "XSRF-TOKEN")).contains("Secure").doesNotContain("HttpOnly");
    }

    @Test
    void bootstrapCsrfCookieIsSecureOverPlainHttp() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();

        assertThat(setCookie(csrf, "XSRF-TOKEN")).contains("Secure");
    }

    private static String setCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
    }
}
