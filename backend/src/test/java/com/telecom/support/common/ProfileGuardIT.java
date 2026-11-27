package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.SupportApplication;
import com.telecom.support.identity.LoginLimitProperties;
import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.StubProdAdapters;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * F3: the {@code e2e} profile relaxes only L1 (01 backend §12 E2.4), and {@code prod} + {@code e2e} never start together
 * (E2.5; NFR-SEC). This class's own context runs as {@code dev,e2e}.
 */
@ActiveProfiles({"dev", "e2e"})
// the shared test database must not be wiped by this context (erratum E5 clean is proven on its own schema below)
@TestPropertySource(properties = "spring.flyway.clean-disabled=true")
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
        // prod has no mock sender/provisioning and ProdAdapterCheck refuses mock-only ports (03 backend §6, §8)
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class, StubProdAdapters.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");

        String[] withStubs = Stream.concat(Arrays.stream(args()), Stream.of("--test.stub-adapters=all"))
                .toArray(String[]::new);
        try (var context = app.run(withStubs)) {
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

    @ParameterizedTest
    @ValueSource(strings = {"--app.notification.mock.enabled=true",
        "--app.reactivation.provisioning.mock.enabled=true", "--app.notification.dispatch-cron=-",
        "--app.reactivation.reconcile-cron=-", "--app.reactivation.expiry-cron=-"})
    void prodWithAnInboundMockOrDisabledJobFailsToStart(String arg) {
        // 03 backend §6, §8: nothing would be delivered / provisioned, or restorations never reconciled or ended
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class, StubProdAdapters.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
        String[] withArg = Stream.concat(Arrays.stream(args()), Stream.of("--test.stub-adapters=all", arg))
                .toArray(String[]::new);

        assertThatThrownBy(() -> app.run(withArg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining(arg.substring(2, arg.indexOf('=')));
    }

    @Test
    void prodWithFlywayCleanEnabledFailsToStart() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
        String[] withClean = Stream.concat(Arrays.stream(args()), Stream.of("--spring.flyway.clean-disabled=false"))
                .toArray(String[]::new);

        assertThatThrownBy(() -> app.run(withClean))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("spring.flyway.clean-disabled");
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
