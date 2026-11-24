package com.telecom.support.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Deep links and reloads of SPA routes (design 01-foundation backend §12 E2.1; XC-02, ADR 0001): a {@code GET} whose last
 * path segment has no dot and whose first segment is not {@code api}, {@code actuator}, {@code error}, {@code v3} or
 * {@code swagger-ui} is forwarded to {@code /index.html}. Everything else is untouched, so unknown {@code /api/**} keeps
 * its 401/404 ProblemDetail and a missing file such as {@code /missing.js} stays 404.
 *
 * <p>A handler mapping rather than a {@code @GetMapping}: path patterns cannot express "any depth, no dot in the last
 * segment". It runs after the annotated controllers and before the static resource mapping ({@code LOWEST_PRECEDENCE - 1}).
 * Segments are compared decoded and without matrix parameters. No security change: SPA routes already fall under
 * {@code anyRequest().permitAll()}.
 */
@Component
public class SpaForwardController extends AbstractHandlerMapping {

    static final Set<String> EXCLUDED_FIRST_SEGMENTS = Set.of("api", "actuator", "error", "v3", "swagger-ui");

    private final ParameterizableViewController forward = new ParameterizableViewController();

    public SpaForwardController() {
        forward.setViewName("forward:/index.html");
        forward.setSupportedMethods(HttpMethod.GET.name());
        setOrder(Ordered.LOWEST_PRECEDENCE - 2);
    }

    @Override
    protected Object getHandlerInternal(@NonNull HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return null;
        }
        RequestPath requestPath = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request);
        List<String> segments = requestPath.pathWithinApplication().elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(e -> ((PathContainer.PathSegment) e).valueToMatch())
                .toList();
        if (segments.isEmpty()) {
            return forward;
        }
        if (EXCLUDED_FIRST_SEGMENTS.contains(segments.get(0)) || segments.get(segments.size() - 1).contains(".")) {
            return null;
        }
        return forward;
    }
}
