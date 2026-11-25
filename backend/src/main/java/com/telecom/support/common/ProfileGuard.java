package com.telecom.support.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Startup guard (NFR-SEC), run as an {@link EnvironmentPostProcessor} (registered in {@code META-INF/spring.factories})
 * after the config data has resolved the active profiles, so it fails before any bean, connection or port is opened.
 * The {@code prod} profile refuses to start when
 * <ul>
 *   <li>{@code e2e} is also active: it relaxes the L1 login limit (design 01-foundation backend §12 E2.5);</li>
 *   <li>{@code app.customer.otp.dev-fixed-code} is set: every contact verification code would be known
 *       (design 02-customer backend §6, §8, OQ 11);</li>
 *   <li>{@code app.payment.mock.enabled} is true: the public mock checkout page would let anyone settle a payment
 *       (design 02-customer backend §4, §6).</li>
 * </ul>
 */
public class ProfileGuard implements EnvironmentPostProcessor, Ordered {

    static final String OTP_DEV_FIXED_CODE = "app.customer.otp.dev-fixed-code";
    static final String PAYMENT_MOCK_ENABLED = "app.payment.mock.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("e2e"))) {
            throw new IllegalStateException("Profiles 'prod' and 'e2e' must not be active together: 'e2e' relaxes the "
                    + "login rate limit and is for local end-to-end tests only (01 backend §12 E2.5)");
        }
        if (StringUtils.hasText(environment.getProperty(OTP_DEV_FIXED_CODE))) {
            throw new IllegalStateException("Property '" + OTP_DEV_FIXED_CODE + "' must not be set with the 'prod' "
                    + "profile: it makes every contact verification code known (02 backend §8, OQ 11)");
        }
        if (Boolean.TRUE.equals(environment.getProperty(PAYMENT_MOCK_ENABLED, Boolean.class, Boolean.FALSE))) {
            throw new IllegalStateException("Property '" + PAYMENT_MOCK_ENABLED + "' must not be true with the 'prod' "
                    + "profile: the mock checkout page is public and settles payments (02 backend §4, §6)");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
