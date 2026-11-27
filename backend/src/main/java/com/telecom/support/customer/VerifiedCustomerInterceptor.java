package com.telecom.support.customer;

import com.telecom.support.common.error.ApiExceptions.VerificationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Central verified scope (design 03-inbound backend R5, R6, §4; INB-01.2, BR-10). Registered by
 * {@link VerifiedCustomerWebConfig} for every {@code /api/v1/agent/customers/{customerId}/**} route except
 * {@code /identity-checks/**}; it runs before the handler, so before any {@code @PreAuthorize}'d logic and before
 * {@code IdempotencyService.execute} (an idempotent replay after expiry is 403 too).
 *
 * <p>A caller holding {@code customer.lookup} (inbound agents) needs {@link VerifiedCustomerAccess#require(long)}; the
 * result is stored as the request attribute {@link #ATTRIBUTE} for the {@link VerifiedCustomer} handler argument, and a
 * successful (2xx) GET logs the first access under the check (R6) in {@link #afterCompletion}. Without
 * {@code customer.lookup} it does nothing and the handler's {@code @PreAuthorize} denies with 403 {@code forbidden}.
 */
@Component
public class VerifiedCustomerInterceptor implements HandlerInterceptor {

    /** Request attribute holding the {@link VerifiedCustomer} of the current request. */
    static final String ATTRIBUTE = VerifiedCustomer.class.getName();
    /** The path variable naming the customer on every verified route. */
    static final String CUSTOMER_ID = "customerId";
    /** The permission that marks an inbound agent (§4); a literal so {@code customer} does not import {@code identity}. */
    static final String CUSTOMER_LOOKUP = "customer.lookup";

    private final VerifiedCustomerAccess access;

    VerifiedCustomerInterceptor(VerifiedCustomerAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod) || !holdsCustomerLookup()) {
            return true;
        }
        VerifiedCustomer verified = access.require(customerId(request));
        request.setAttribute(ATTRIBUTE, verified);
        return true;
    }

    /**
     * R6: the access row is written only for a protected read that succeeded (2xx GET), so after {@code @PreAuthorize}
     * and the handler. A denied read (for example I13 without {@code reactivation.approve}) must not use up the
     * once-per-check row (review C3).
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        if (ex == null && request.getAttribute(ATTRIBUTE) instanceof VerifiedCustomer verified
                && HttpMethod.GET.matches(request.getMethod())
                && response.getStatus() >= 200 && response.getStatus() < 300) {
            access.logFirstAccess(verified, request.getRequestURI());
        }
    }

    /** True when the current principal holds {@code customer.lookup}. */
    static boolean holdsCustomerLookup() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (CUSTOMER_LOOKUP.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** The {@code customerId} path variable; missing or not a number → no check can exist → 403. */
    private static long customerId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object value = vars instanceof Map<?, ?> map ? map.get(CUSTOMER_ID) : null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new VerificationRequiredException("No numeric customerId on " + request.getRequestURI());
        }
    }
}
