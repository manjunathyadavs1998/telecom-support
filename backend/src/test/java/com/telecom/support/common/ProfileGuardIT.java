package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.SupportApplication;
import com.telecom.support.identity.LoginLimitProperties;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.context.ActiveProfiles;

/**
 * F3: the {@code e2e} profile relaxes only L1 (01 backend §12 E2.4), and {@code prod} + {@code e2e} never start together
 * (E2.5; NFR-SEC). This class's own context runs as {@code dev,e2e}.
 */
@ActiveProfiles({"dev", "e2e"})
class ProfileGuardIT extends AbstractMySqlIT {

    @Autowired
    LoginLimitProperties limits;

    @Test
    void devPlusE2eStartsAndRaisesOnlyTheIpLimit() {
        assertThat(limits).isEqualTo(new LoginLimitProperties(100, 5, Duration.ofMinutes(15), 50));
    }

    @Test
    void prodPlusE2eFailsToStartNamingBothProfiles() {
        // the same complete configuration that starts in prodAloneStillStarts: only the guard can stop it
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod", "e2e");

        assertThatThrownBy(() -> app.run(args()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("'e2e'");
    }

    @Test
    void prodAloneStillStarts() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");

        try (var context = app.run(args())) {
            assertThat(context.getBean(LoginLimitProperties.class).ipAttemptsPerMinute()).isEqualTo(20);
        }
    }

    /** Command-line arguments, so they win over application.yml (as in a real deployment's environment). */
    private static String[] args() {
        return new String[] {
            "--DB_URL=" + MYSQL.getJdbcUrl(),
            "--DB_USERNAME=" + MYSQL.getUsername(),
            "--DB_PASSWORD=" + MYSQL.getPassword(),
            "--spring.flyway.locations=classpath:db/migration,classpath:db/test-data",
            "--server.port=0"
        };
    }
}
