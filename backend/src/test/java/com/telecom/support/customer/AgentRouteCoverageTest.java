package com.telecom.support.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Route coverage of the central verified scope (design 03-inbound backend R5, §4, §9; BR-10). Every handler under
 * {@code /api/v1/agent/customers/...} must name the customer {@code {customerId}}, and every one except
 * {@code /identity-checks/**} must take a {@link VerifiedCustomer} and sit under the interceptor's patterns. A new
 * agent route that forgets the parameter fails the build. Pure reflection over every {@code @RestController} on the
 * classpath (including test probes); no Spring context.
 */
class AgentRouteCoverageTest {

    private static final String CUSTOMERS = "/api/v1/agent/customers/";
    private static final PathPattern INCLUDE = PathPatternParser.defaultInstance.parse(
            VerifiedCustomerWebConfig.VERIFIED_ROUTES);
    private static final PathPattern EXCLUDE = PathPatternParser.defaultInstance.parse(
            VerifiedCustomerWebConfig.IDENTITY_CHECK_ROUTES);

    record Route(String path, Method handler) {
        @Override
        public String toString() {
            return path + " -> " + handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
        }
    }

    @Test
    void everyCustomerRouteExceptIdentityChecksTakesAVerifiedCustomerAndIsIntercepted() throws Exception {
        List<Route> customerRoutes = routes().stream().filter(r -> r.path().startsWith(CUSTOMERS)).toList();
        assertThat(customerRoutes).as("agent customer routes").isNotEmpty();

        List<Route> verified = new ArrayList<>();
        for (Route route : customerRoutes) {
            assertThat(route.path()).as(route.toString()).startsWith(CUSTOMERS + "{customerId}");
            PathContainer path = PathContainer.parsePath(route.path());
            if (EXCLUDE.matches(path)) {
                continue;
            }
            verified.add(route);
            assertThat(INCLUDE.matches(path)).as("intercepted: " + route).isTrue();
            assertThat(route.handler().getParameterTypes()).as("VerifiedCustomer parameter: " + route)
                    .contains(VerifiedCustomer.class);
        }
        assertThat(verified).as("verified routes").isNotEmpty();
        assertThat(verified).extracting(Route::path).as("the shipped verified routes are covered").contains(
                CUSTOMERS + "{customerId}/profile", CUSTOMERS + "{customerId}/subscriptions",
                CUSTOMERS + "{customerId}/invoices", CUSTOMERS + "{customerId}/payments",
                CUSTOMERS + "{customerId}/cases", CUSTOMERS + "{customerId}/cases/{reference}");
        assertThat(verified).filteredOn(r -> r.path().equals(CUSTOMERS + "{customerId}/cases"))
                .extracting(r -> r.handler().getName()).as("I9 and I12").contains("listCases", "createOnBehalf");
    }

    @Test
    void agentRoutesOutsideACustomerAreNotBehindTheVerifiedScope() {
        assertThat(INCLUDE.matches(PathContainer.parsePath("/api/v1/agent/case-categories"))).isFalse();
        assertThat(INCLUDE.matches(PathContainer.parsePath("/api/v1/agent/customer-searches"))).isFalse();
    }

    @Test
    void identityCheckRoutesAreExcludedAndNothingElse() {
        assertThat(EXCLUDE.matches(PathContainer.parsePath(CUSTOMERS + "{customerId}/identity-checks"))).isTrue();
        assertThat(EXCLUDE.matches(PathContainer.parsePath(CUSTOMERS + "{customerId}/identity-checks/current")))
                .isTrue();
        assertThat(EXCLUDE.matches(PathContainer.parsePath(CUSTOMERS + "{customerId}/profile"))).isFalse();
        assertThat(INCLUDE.matches(PathContainer.parsePath(CUSTOMERS + "{customerId}/profile"))).isTrue();
    }

    /** Every (class path × method path) of every {@code @RestController} under {@code com.telecom.support}. */
    private static List<Route> routes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Route> routes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.telecom.support")) {
            Class<?> type = ClassUtils.forName(definition.getBeanClassName(), AgentRouteCoverageTest.class
                    .getClassLoader());
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> prefixes = typeMapping == null || typeMapping.path().length == 0 ? List.of("")
                    : Arrays.asList(typeMapping.path());
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> paths = mapping.path().length == 0 ? List.of("") : Arrays.asList(mapping.path());
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        routes.add(new Route(prefix + path, method));
                    }
                }
            }
        }
        return routes;
    }
}
