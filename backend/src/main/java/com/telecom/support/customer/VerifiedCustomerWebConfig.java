package com.telecom.support.customer;

import com.telecom.support.common.error.ApiExceptions.VerificationRequiredException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the central verified scope (design 03-inbound backend R5): {@link VerifiedCustomerInterceptor} on
 * {@link #VERIFIED_ROUTES} minus {@link #IDENTITY_CHECK_ROUTES}, and the {@link VerifiedCustomer} handler argument.
 * {@code AgentRouteCoverageTest} checks that every handler under these routes takes a {@link VerifiedCustomer}.
 */
@Configuration
public class VerifiedCustomerWebConfig implements WebMvcConfigurer {

    public static final String VERIFIED_ROUTES = "/api/v1/agent/customers/{customerId}/**";
    public static final String IDENTITY_CHECK_ROUTES = "/api/v1/agent/customers/{customerId}/identity-checks/**";

    private final VerifiedCustomerInterceptor interceptor;

    VerifiedCustomerWebConfig(VerifiedCustomerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(VERIFIED_ROUTES).excludePathPatterns(IDENTITY_CHECK_ROUTES);
    }

    @Override
    public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new VerifiedCustomerArgumentResolver());
    }

    /**
     * Supplies the {@link VerifiedCustomer} the interceptor stored. Fails closed when it is absent: an inbound agent gets
     * 403 {@code verification-required}, anyone else 403 {@code forbidden}.
     */
    static final class VerifiedCustomerArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(@NonNull MethodParameter parameter) {
            return VerifiedCustomer.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(@NonNull MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      @NonNull NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            Object verified = webRequest.getAttribute(VerifiedCustomerInterceptor.ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
            if (verified instanceof VerifiedCustomer customer) {
                return customer;
            }
            if (VerifiedCustomerInterceptor.holdsCustomerLookup()) {
                throw new VerificationRequiredException("Handler " + parameter.getExecutable().getName()
                        + " is not covered by the verified-customer interceptor");
            }
            throw new AccessDeniedException("Verified customer route without customer.lookup");
        }
    }
}
