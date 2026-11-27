package com.telecom.support.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Startup check under {@code prod} (design 03-inbound backend §6; NFR-SEC): after every singleton is created it fails
 * startup when an outbound port is implemented only by an in-memory {@code Mock*} bean (or by none), so production never
 * runs with fake delivery, payment or provisioning. Property-level checks are {@link ProfileGuard}'s.
 *
 * <p>The ports are named by class name, not imported: {@code common} must not depend on feature modules (ADR 0001).
 */
@Component
@Profile("prod")
public class ProdAdapterCheck implements SmartInitializingSingleton {

    /** The outbound ports that need a real adapter in prod. */
    static final List<String> PORTS = List.of(
            "com.telecom.support.reactivation.ProvisioningClient",
            "com.telecom.support.notification.NotificationSender",
            "com.telecom.support.customer.VerificationCodeSender",
            "com.telecom.support.payment.PaymentGateway",
            "com.telecom.support.customer.PlanProvisioning");

    private final ListableBeanFactory beans;

    ProdAdapterCheck(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> mockOnly = mockOnlyPorts(beans, getClass().getClassLoader());
        if (!mockOnly.isEmpty()) {
            throw new IllegalStateException("The 'prod' profile needs a real adapter for " + String.join(", ", mockOnly)
                    + ": only an in-memory Mock* implementation is present (03 backend §6)");
        }
    }

    /** Simple names of the {@link #PORTS} whose beans are all {@code Mock*} classes, or that have no bean. */
    static List<String> mockOnlyPorts(ListableBeanFactory beans, ClassLoader classLoader) {
        List<String> mockOnly = new ArrayList<>();
        for (String port : PORTS) {
            Class<?> type = ClassUtils.resolveClassName(port, classLoader);
            Map<String, ?> implementations = beans.getBeansOfType(type);
            boolean real = implementations.values().stream()
                    .map(bean -> ClassUtils.getUserClass(AopUtils.getTargetClass(bean)))
                    .anyMatch(implementation -> !implementation.getSimpleName().startsWith("Mock"));
            if (!real) {
                mockOnly.add(type.getSimpleName());
            }
        }
        return mockOnly;
    }
}
