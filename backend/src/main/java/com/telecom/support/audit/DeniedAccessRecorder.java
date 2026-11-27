package com.telecom.support.audit;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.common.error.ApiExceptions.VerificationRequiredException;
import com.telecom.support.common.security.AccessDeniedListener;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorityAuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code ACCESS_DENIED} audit row for a 403 (01 backend §4.4, §7; BR-03, BR-10, XC-06). As the
 * {@link AccessDeniedListener} it is called exactly once per denial: by the security chain's
 * {@code AccessDeniedHandler} (URL-level and CSRF) and by {@code GlobalExceptionHandler} ({@code @PreAuthorize}).
 * The actor comes from the {@link ActorProvider} port, so this module does not depend on {@code identity}.
 *
 * <p>Sampled: at most one row per (user + path) per minute for an authenticated caller, and one row per client IP per
 * minute for an anonymous one, whatever the path, so varying the path cannot force writes. The stored path is
 * truncated to {@value #MAX_PATH_LENGTH} characters. The sampling map is bounded:
 * when it is full, expired keys are dropped first, and if it is still full it is cleared, so overflow can only write
 * more rows, never fewer.
 */
@Component
public class DeniedAccessRecorder implements AccessDeniedListener {

    static final String ACTION = "ACCESS_DENIED";
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int DEFAULT_MAX_KEYS = 10_000;
    static final int MAX_PATH_LENGTH = 255;

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final AuditService audit;
    private final ActorProvider actors;
    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Instant> lastRecorded = new ConcurrentHashMap<>();

    @Autowired
    public DeniedAccessRecorder(AuditService audit, ActorProvider actors, Clock clock) {
        this(audit, actors, clock, DEFAULT_MAX_KEYS);
    }

    DeniedAccessRecorder(AuditService audit, ActorProvider actors, Clock clock, int maxKeys) {
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    @Override
    public void onAccessDenied(HttpServletRequest request, AccessDeniedException denial) {
        record(request, denial);
    }

    /** Records the denial unless the same user + path (or, anonymous, the same client IP) was recorded in the window. */
    public void record(HttpServletRequest request, AccessDeniedException denial) {
        Actor user = actors.currentActor().orElse(null);
        String path = truncate(request.getRequestURI());
        String key = user != null ? "u:" + user.id() + " " + path : "ip:" + ClientIp.resolve(request);
        if (!sample(key, clock.instant())) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", request.getMethod());
        details.put("path", path);
        details.put("reason", reason(denial));
        String required = requiredAuthority(denial);
        if (required != null) {
            details.put("requiredAuthority", required);
        }
        audit.recordIndependently(ACTION, Outcome.DENIED, user != null ? user.id() : null,
                user != null ? user.username() : null, null, null, details);
    }

    /** {@code csrf}, {@code verification_required} (03 backend §4) or {@code permission}. */
    static String reason(AccessDeniedException denial) {
        if (denial instanceof CsrfException) {
            return "csrf";
        }
        return denial instanceof VerificationRequiredException ? "verification_required" : "permission";
    }

    /** The path as stored in {@code details} and in the sampling key: at most {@value #MAX_PATH_LENGTH} characters. */
    static String truncate(String path) {
        return path != null && path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path;
    }

    /** Number of keys held for sampling (bounded by {@code maxKeys}). */
    int trackedKeys() {
        return lastRecorded.size();
    }

    private boolean sample(String key, Instant now) {
        if (!lastRecorded.containsKey(key) && lastRecorded.size() >= maxKeys) {
            Instant cutoff = now.minus(WINDOW);
            lastRecorded.values().removeIf(at -> !at.isAfter(cutoff));
            if (lastRecorded.size() >= maxKeys) {
                lastRecorded.clear();
            }
        }
        boolean[] write = {false};
        lastRecorded.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(WINDOW))) {
                write[0] = true;
                return now;
            }
            return last;
        });
        return write[0];
    }

    /** The authority the denied check required, when the decision says so; otherwise null. */
    static String requiredAuthority(AccessDeniedException denial) {
        if (!(denial instanceof AuthorizationDeniedException denied)) {
            return null;
        }
        AuthorizationResult result = denied.getAuthorizationResult();
        if (result instanceof AuthorityAuthorizationDecision decision) {
            return decision.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted()
                    .collect(Collectors.joining(","));
        }
        if (result instanceof ExpressionAuthorizationDecision decision) {
            return fromHasAuthority(decision.getExpression());
        }
        return null;
    }

    /** The argument of a plain {@code hasAuthority(x)} expression (literal or {@code T(Permissions).X}); else null. */
    private static String fromHasAuthority(Expression expression) {
        if (!(expression instanceof SpelExpression spel)
                || !(spel.getAST() instanceof MethodReference method)
                || !"hasAuthority".equals(method.getName()) || method.getChildCount() != 1) {
            return null;
        }
        SpelNode argument = method.getChild(0);
        try {
            Object value = PARSER.parseExpression(argument.toStringAST()).getValue(new StandardEvaluationContext());
            return value instanceof String s ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
