package com.telecom.support.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fresh database per end-to-end run (design 02-customer backend erratum E5, frontend design D2): under the {@code e2e}
 * profile, which sets {@code spring.flyway.clean-disabled=false}, Flyway cleans the schema and then migrates it (with
 * the dev seed) at startup. {@link ProfileGuard} refuses {@code e2e} and enabled clean together with {@code prod}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
@ConditionalOnProperty(name = "spring.flyway.clean-disabled", havingValue = "false")
class E2eDatabaseReset {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
