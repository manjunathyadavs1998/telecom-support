package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.SupportApplication;
import com.telecom.support.identity.LoginLimitProperties;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Duration;
import java.util.Arrays;
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

    @Test
    void prodWithoutAReturnUrlBaseFailsToStart() {
        // application-prod.yml has no default for it (erratum E4), unlike the localhost default of other profiles
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
        String[] withoutReturnUrlBase = Arrays.stream(args())
                .filter(arg -> !arg.startsWith("--PAYMENT_RETURN_URL_BASE="))
                .toArray(String[]::new);

        assertThatThrownBy(() -> app.run(withoutReturnUrlBase))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("app.payment.return-url-base");
    }

    @Test
    void prodWithoutTheHmacSecretsFailsToStart() {
        // blank secrets would give each instance its own random key (review C1)
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
        String[] withoutSecrets = Arrays.stream(args())
                .filter(arg -> !arg.startsWith("--OTP_HMAC_SECRET=") && !arg.startsWith("--PAYMENT_WEBHOOK_SECRET="))
                .toArray(String[]::new);

        assertThatThrownBy(() -> app.run(withoutSecrets))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("app.customer.otp.hmac-secret");
    }

    /**
     * Command-line arguments, so they win over application.yml (as in a real deployment's environment), including the
     * values prod requires (review C1, erratum E4).
     */
    private static String[] args() {
        return new String[] {
            "--DB_URL=" + MYSQL.getJdbcUrl(),
            "--DB_USERNAME=" + MYSQL.getUsername(),
            "--DB_PASSWORD=" + MYSQL.getPassword(),
            "--spring.flyway.locations=classpath:db/migration,classpath:db/test-data",
            "--server.port=0",
            "--OTP_HMAC_SECRET=it-otp-secret",
            "--PAYMENT_WEBHOOK_SECRET=it-webhook-secret",
            "--PAYMENT_RETURN_URL_BASE=https://portal.example"
        };
    }
}
