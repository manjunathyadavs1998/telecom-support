package com.telecom.support.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Startup guard (design 01-foundation backend §12 E2.5; NFR-SEC): the {@code e2e} profile relaxes the L1 login limit, so the
 * application refuses to start when {@code prod} and {@code e2e} are both active. Runs as an {@link EnvironmentPostProcessor}
 * (registered in {@code META-INF/spring.factories}) after the config data has resolved the active profiles, so it fails
 * before any bean, connection or port is opened.
 */
public class ProfileGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("prod & e2e"))) {
            throw new IllegalStateException("Profiles 'prod' and 'e2e' must not be active together: 'e2e' relaxes the "
                    + "login rate limit and is for local end-to-end tests only (01 backend §12 E2.5)");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
