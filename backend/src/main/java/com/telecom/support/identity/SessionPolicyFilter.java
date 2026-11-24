package com.telecom.support.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session policy (design 01-foundation backend §4.3), placed right after the security context is loaded. For a
 * signed-in session it enforces the absolute lifetime ({@code app.session.absolute-timeout}, measured from login on
 * {@code AppClock}) and re-checks {@code users.status} on every request. A violating session is invalidated and the
 * context cleared, so a protected request continues as anonymous and gets the 401 problem. The idle timeout is
 * Spring Session's own ({@code spring.session.timeout}). Not a bean: added to the security chain only.
 */
final class SessionPolicyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionPolicyFilter.class);

    /** Session attribute set by {@link AuthController} at login: epoch millis on {@code AppClock}. */
    static final String LOGIN_AT = SessionPolicyFilter.class.getName() + ".LOGIN_AT";

    private final UserRepository users;
    private final Clock clock;
    private final Duration absoluteTimeout;

    SessionPolicyFilter(UserRepository users, Clock clock, Duration absoluteTimeout) {
        this.users = users;
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            String reason = violation(session, user);
            if (reason != null) {
                log.info("Session ended for user {}: {}", user.id(), reason);
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String violation(HttpSession session, CurrentUser user) {
        Object loginAt = session.getAttribute(LOGIN_AT);
        if (!(loginAt instanceof Long millis) || clock.millis() - millis >= absoluteTimeout.toMillis()) {
            return "absolute lifetime exceeded";
        }
        if (!users.isActive(user.id())) {
            return "user disabled";
        }
        return null;
    }
}
