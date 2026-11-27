package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.SupportApplication;
import com.telecom.support.customer.MockVerificationCodeSender;
import com.telecom.support.notification.MockNotificationSender;
import com.telecom.support.notification.NotificationSender;
import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.StubProdAdapters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * B15: under {@code prod}, {@link ProdAdapterCheck} fails startup while an outbound port has only a {@code Mock*}
 * implementation (design 03-inbound backend §6, §8; NFR-SEC). Property-level refusals are in {@code ProfileGuardIT}.
 * The prod applications use {@link StubProdAdapters} for the ports a test wants "real".
 */
class ProdAdapterCheckIT extends AbstractMySqlIT {

    @Test
    void prodWithOnlyMockAdaptersFailsNamingThePorts() {
        // NotificationSender and ProvisioningClient stubbed; the 02 ports keep only their unconditional mocks
        SpringApplicationBuilder app = prodApp();

        assertThatThrownBy(() -> app.run(args("new-ports")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("VerificationCodeSender")
                .hasMessageContaining("PaymentGateway")
                .hasMessageContaining("PlanProvisioning")
                .hasMessageNotContaining("NotificationSender")
                .hasMessageNotContaining("ProvisioningClient");
    }

    @Test
    void prodWithANonMockAdapterForEveryPortStarts() {
        try (var context = prodApp().run(args("all"))) {
            assertThat(context.getBeansOfType(ProdAdapterCheck.class)).hasSize(1);
        }
    }

    @Test
    void portsWithOnlyMocksOrNoBeanAreReported() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mockNotificationSender", new MockNotificationSender());
        beans.registerSingleton("mockVerificationCodeSender", new MockVerificationCodeSender());

        assertThat(ProdAdapterCheck.mockOnlyPorts(beans, getClass().getClassLoader())).containsExactly(
                "ProvisioningClient", "NotificationSender", "VerificationCodeSender", "PaymentGateway",
                "PlanProvisioning");

        beans.registerSingleton("realNotificationSender", (NotificationSender) (channel, destination, template,
                params) -> { });
        assertThat(ProdAdapterCheck.mockOnlyPorts(beans, getClass().getClassLoader()))
                .doesNotContain("NotificationSender").contains("VerificationCodeSender");
    }

    private static SpringApplicationBuilder prodApp() {
        return new SpringApplicationBuilder(SupportApplication.class, StubProdAdapters.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
    }

    /** As {@code ProfileGuardIT}: the values prod requires, plus which ports get a stand-in adapter. */
    private static String[] args(String stubAdapters) {
        return new String[] {
            "--DB_URL=" + MYSQL.getJdbcUrl(),
            "--DB_USERNAME=" + MYSQL.getUsername(),
            "--DB_PASSWORD=" + MYSQL.getPassword(),
            "--spring.flyway.locations=classpath:db/migration,classpath:db/test-data",
            "--server.port=0",
            "--OTP_HMAC_SECRET=it-otp-secret",
            "--PAYMENT_WEBHOOK_SECRET=it-webhook-secret",
            "--PAYMENT_RETURN_URL_BASE=https://portal.example",
            "--test.stub-adapters=" + stubAdapters
        };
    }
}
