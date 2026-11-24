package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.common.web.ClientIp;
import com.telecom.support.common.web.CorrelationIdFilter;
import com.telecom.support.common.web.TrustedProxyFilter;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * B8b: client IP behind trusted proxies only (01 backend §4.4, §8; 00-overview §5; NFR-SEC, XC-06). The login limiter
 * key (L1: 20 attempts per IP per minute) and audit {@code client_ip} must both use {@code ClientIp.resolve}. The outer
 * class runs with the default (empty) {@code app.security.trusted-proxies}; {@link BehindTrustedProxy} trusts
 * {@code 10.99.0.0/16} and {@code fd00:abcd::/32}. Limiter state is per context, so every test uses its own addresses.
 */
class ClientIpIT extends AbstractMySqlIT {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String TOKEN = "client-ip-token";
    private static final int IP_LIMIT = 20;
    private static final AtomicInteger USERS = new AtomicInteger();
    /** Fixed time, so the L1 bucket cannot refill while the 21 attempts run (slow BCrypt hosts made this flaky). */
    private static final IdempotencyServiceIT.MutableClock CLOCK =
            new IdempotencyServiceIT.MutableClock(Instant.parse("2026-04-01T09:00:00Z"));

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    static Clock fixedClock() {
        return CLOCK;
    }

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ServerProperties serverProperties;

    @Autowired
    SecurityProperties securityProperties;

    @Test
    void forgedForwardedHeadersAreIgnoredWithoutTrustedProxies() throws Exception {
        String remote = "192.0.2.10";
        MvcResult first = failedLogin(mvc, remote, Map.of(
                "X-Forwarded-For", "198.51.100.77",
                "Forwarded", "for=198.51.100.78")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(auditClientIp(jdbc, first)).isEqualTo(remote);

        // every attempt claims another client, yet all count against the remote address
        for (int i = 1; i < IP_LIMIT; i++) {
            failedLogin(mvc, remote, Map.of("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().isUnauthorized());
        }
        failedLogin(mvc, remote, Map.of("X-Forwarded-For", "198.51.100.200"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void filterOrderIsTrustedProxyThenCorrelationIdThenSecurityChain() {
        int proxy = OrderUtils.getOrder(TrustedProxyFilter.class, Ordered.LOWEST_PRECEDENCE);
        int correlation = OrderUtils.getOrder(CorrelationIdFilter.class, Ordered.LOWEST_PRECEDENCE);
        assertThat(proxy).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(correlation).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(securityProperties.getFilter().getOrder()).isEqualTo(-100).isGreaterThan(correlation);
        assertThat(context.getBeansOfType(TrustedProxyFilter.class)).hasSize(1);

        // Spring's own forwarded-header handling is off, so nothing can apply the headers around TrustedProxyFilter
        assertThat(serverProperties.getForwardHeadersStrategy()).isEqualTo(ServerProperties.ForwardHeadersStrategy.NONE);
        assertThat(context.getBeansOfType(ForwardedHeaderFilter.class)).isEmpty();
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .noneMatch(r -> r.getFilter() instanceof ForwardedHeaderFilter);
    }

    @Test
    void emptyTrustedListStripsForwardedHeadersAndKeepsTheRemoteAddress() throws Exception {
        HttpServletRequest seen = filter(new TrustedProxyFilter(List.of()), "192.0.2.20", Map.of(
                "X-Forwarded-For", "198.51.100.5",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "evil.example"));
        assertThat(seen.getRemoteAddr()).isEqualTo("192.0.2.20");
        assertThat(ClientIp.resolve(seen)).isEqualTo("192.0.2.20");
        assertThat(seen.getHeader("X-Forwarded-For")).isNull();
        assertThat(seen.getHeader("X-Forwarded-Proto")).isNull();
        assertThat(seen.isSecure()).isFalse();
        assertThat(seen.getServerName()).isEqualTo("localhost");
    }

    @Test
    void trustedProxyAppliesProtoAndStopsAtAnUnparseableHop() throws Exception {
        HttpServletRequest seen = filter(new TrustedProxyFilter(List.of("10.99.0.0/16")), "10.99.0.5", Map.of(
                "X-Forwarded-For", "unknown, 10.99.1.1",
                "X-Forwarded-Proto", "https"));
        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.getRemoteAddr()).isEqualTo("10.99.1.1");
        assertThat(ClientIp.resolve(seen)).isEqualTo("10.99.1.1");
        assertThat(seen.getHeader("X-Forwarded-For")).isNull();
    }

    @Test
    void invalidTrustedProxyEntryFailsStartup() {
        assertThatThrownBy(() -> new TrustedProxyFilter(List.of("proxy.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @TestPropertySource(properties = {
            "app.security.trusted-proxies[0]=10.99.0.0/16",
            "app.security.trusted-proxies[1]=fd00:abcd::/32"})
    class BehindTrustedProxy {

        @Autowired
        MockMvc proxiedMvc;

        @Autowired
        NamedParameterJdbcTemplate proxiedJdbc;

        @Test
        void forwardedForFromATrustedProxyKeysTheLimiterAndAudit() throws Exception {
            String client = "198.51.100.20";
            MvcResult first = failedLogin(proxiedMvc, "10.99.0.5", Map.of("X-Forwarded-For", client))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(auditClientIp(proxiedJdbc, first)).isEqualTo(client);

            // the same client through two proxies shares one bucket
            for (int i = 1; i < IP_LIMIT; i++) {
                String proxy = i % 2 == 0 ? "10.99.0.5" : "10.99.0.6";
                failedLogin(proxiedMvc, proxy, Map.of("X-Forwarded-For", client)).andExpect(status().isUnauthorized());
            }
            failedLogin(proxiedMvc, "10.99.0.5", Map.of("X-Forwarded-For", client))
                    .andExpect(status().isTooManyRequests());
            // another client behind the same proxy is not throttled: the key is the client, not the proxy
            failedLogin(proxiedMvc, "10.99.0.5", Map.of("X-Forwarded-For", "198.51.100.21"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void multiHopTakesTheRightMostUntrustedAddress() throws Exception {
            MvcResult xff = failedLogin(proxiedMvc, "10.99.0.5",
                    Map.of("X-Forwarded-For", "203.0.113.66, 198.51.100.30, 10.99.7.7"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(auditClientIp(proxiedJdbc, xff)).isEqualTo("198.51.100.30");

            MvcResult rfc7239 = failedLogin(proxiedMvc, "fd00:abcd::1",
                    Map.of("Forwarded", "for=203.0.113.9, for=\"[2001:db8::30]:4711\";proto=https, for=10.99.0.9"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(auditClientIp(proxiedJdbc, rfc7239)).isEqualTo("2001:db8::30");
        }

        @Test
        void forgedForwardedForFromAnUntrustedAddressIsStillIgnored() throws Exception {
            MvcResult result = failedLogin(proxiedMvc, "192.0.2.30", Map.of("X-Forwarded-For", "10.99.0.1"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(auditClientIp(proxiedJdbc, result)).isEqualTo("192.0.2.30");
        }
    }

    // --- helpers (static: nested tests run against their own context) ---------------------------------------------

    /** A failed login (unknown username, so L2 never blocks) from {@code remote} with the given extra headers. */
    private static org.springframework.test.web.servlet.ResultActions failedLogin(
            MockMvc mvc, String remote, Map<String, String> headers) throws Exception {
        var request = post(LOGIN)
                .with(r -> {
                    r.setRemoteAddr(remote);
                    return r;
                })
                .cookie(new Cookie("XSRF-TOKEN", TOKEN))
                .header("X-XSRF-TOKEN", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"client-ip-" + USERS.incrementAndGet() + "\",\"password\":\"Wrong-1\"}");
        headers.forEach(request::header);
        return mvc.perform(request);
    }

    private static String auditClientIp(NamedParameterJdbcTemplate jdbc, MvcResult result) {
        return jdbc.queryForObject("""
                SELECT client_ip FROM audit_events WHERE correlation_id = :cid AND action = 'LOGIN_FAILURE'
                """, Map.of("cid", result.getResponse().getHeader(CorrelationIdFilter.HEADER)), String.class);
    }

    /** Runs {@code filter} alone and returns the request the next filter would see. */
    private static HttpServletRequest filter(TrustedProxyFilter filter, String remote, Map<String, String> headers)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.setRemoteAddr(remote);
        headers.forEach(request::addHeader);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
