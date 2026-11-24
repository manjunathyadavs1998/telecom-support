package com.telecom.support.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one client IP used by the login limiter and by audit {@code client_ip} (01 backend §4.4, 00-overview §5).
 *
 * <p>{@link TrustedProxyFilter} stores the resolved address on the request: the remote address, or (only when that
 * address is a trusted proxy) the address taken from the forwarded headers. Without the filter (e.g. a request bound
 * by hand in a test) the remote address is used, which never trusts a client-supplied header.
 */
public final class ClientIp {

    /** Request attribute holding the address resolved by {@link TrustedProxyFilter}. */
    public static final String ATTRIBUTE = ClientIp.class.getName();

    private ClientIp() {}

    /** The resolved client IP of {@code request}. */
    public static String resolve(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String ip ? ip : request.getRemoteAddr();
    }

    /** The resolved client IP of the request bound to this thread, or empty outside a request. */
    public static Optional<String> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable(resolve(attrs.getRequest()));
        }
        return Optional.empty();
    }
}
