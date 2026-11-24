package com.telecom.support.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies forwarded headers only from trusted proxies (01 backend §4.4, 00-overview §5; NFR-SEC).
 *
 * <p>When the remote address matches {@code app.security.trusted-proxies} (CIDR list, IPv4 and IPv6), Spring's
 * {@link ForwardedHeaderFilter} applies {@code Forwarded} / {@code X-Forwarded-*} (scheme, host, port) and the client
 * IP is the right-most hop that is not a trusted proxy ({@code Forwarded: for=} if present, else
 * {@code X-Forwarded-For}). Otherwise (always, when the list is empty) the headers are removed and the remote address is
 * the client IP. The result is stored for {@link ClientIp#resolve} and returned by {@code getRemoteAddr()} downstream.
 * {@code server.forward-headers-strategy} is {@code none}, so neither Tomcat nor Spring Boot applies the headers on
 * their own and this filter cannot be bypassed.
 *
 * <p>Ordered {@code HIGHEST_PRECEDENCE}: before {@link CorrelationIdFilter} and the Spring Security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedProxyFilter extends OncePerRequestFilter {

    public static final String PROPERTY = "app.security.trusted-proxies";

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final int MAX_LENGTH = 45; // audit_events.client_ip

    private final List<IpAddressMatcher> trusted;
    private final ForwardedHeaderFilter apply = new ForwardedHeaderFilter();
    private final ForwardedHeaderFilter remove = new ForwardedHeaderFilter();

    @Autowired
    public TrustedProxyFilter(Environment environment) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of()));
    }

    /** @param trustedProxies CIDR blocks or single addresses; an invalid entry fails startup */
    public TrustedProxyFilter(List<String> trustedProxies) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : trustedProxies) {
            if (cidr != null && !cidr.isBlank()) {
                String block = cidr.trim();
                int slash = block.indexOf('/');
                if (literal(slash < 0 ? block : block.substring(0, slash)) == null) {
                    throw new IllegalArgumentException(PROPERTY + " entry is not an IP or CIDR: " + block);
                }
                matchers.add(new IpAddressMatcher(block));
            }
        }
        this.trusted = Collections.unmodifiableList(matchers);
        this.remove.setRemoveOnly(true);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) {
            request.setAttribute(ClientIp.ATTRIBUTE, remote);
            remove.doFilter(request, response, chain);
            return;
        }
        String client = clientIp(request, remote);
        request.setAttribute(ClientIp.ATTRIBUTE, client);
        apply.doFilter(request, response, (req, res) -> chain.doFilter(new ClientAddressRequest(
                (HttpServletRequest) req, client), res));
    }

    /** Right-most hop that is not trusted; stops at the last trusted address if a hop is not an IP literal. */
    private String clientIp(HttpServletRequest request, String remote) {
        List<String> hops = forwardedFor(request);
        String client = remote;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = literal(hops.get(i));
            if (hop == null) {
                break;
            }
            client = hop;
            if (!isTrusted(hop)) {
                break;
            }
        }
        return client;
    }

    private static List<String> forwardedFor(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        List<String> forwarded = Collections.list(request.getHeaders("Forwarded"));
        if (!forwarded.isEmpty()) {
            for (String header : forwarded) {
                for (String element : header.split(",", -1)) {
                    String forValue = "";
                    for (String pair : element.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0 && pair.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals("for")) {
                            forValue = pair.substring(eq + 1);
                        }
                    }
                    hops.add(forValue);
                }
            }
            return hops;
        }
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            hops.addAll(List.of(header.split(",", -1)));
        }
        return hops;
    }

    private boolean isTrusted(String address) {
        if (address == null || trusted.isEmpty() || literal(address) == null) {
            return false;
        }
        for (IpAddressMatcher matcher : trusted) {
            if (matcher.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The IP literal in a header hop ({@code 1.2.3.4}, {@code 1.2.3.4:80}, {@code 2001:db8::1}, {@code "[2001:db8::1]:80"}),
     * or {@code null} if it is anything else (names, {@code unknown}, obfuscated ids). Never triggers a DNS lookup.
     */
    static String literal(String hop) {
        if (hop == null) {
            return null;
        }
        String s = hop.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) {
                return null;
            }
            s = s.substring(1, end);
        } else if (s.indexOf(':') > 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            s = s.substring(0, s.indexOf(':')); // IPv4 with port
        }
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return null;
        }
        var v4 = IPV4.matcher(s);
        if (v4.matches()) {
            for (int g = 1; g <= 4; g++) {
                if (Integer.parseInt(v4.group(g)) > 255) {
                    return null;
                }
            }
            return s;
        }
        if (s.indexOf(':') < 0 || !IPV6.matcher(s).matches()) {
            return null;
        }
        try {
            InetAddress.getByName(s); // contains ':' → parsed as an IPv6 literal, never resolved
            return s;
        } catch (UnknownHostException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Reports the resolved client IP as the remote address to everything downstream. */
    private static final class ClientAddressRequest extends HttpServletRequestWrapper {
        private final String client;

        ClientAddressRequest(HttpServletRequest request, String client) {
            super(request);
            this.client = client;
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client;
        }
    }
}
