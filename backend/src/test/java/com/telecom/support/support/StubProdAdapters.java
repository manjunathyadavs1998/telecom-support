package com.telecom.support.support;

import com.telecom.support.customer.PlanProvisioning;
import com.telecom.support.customer.VerificationCodeSender;
import com.telecom.support.notification.NotificationSender;
import com.telecom.support.payment.PaymentGateway;
import com.telecom.support.reactivation.ProvisioningClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;

/**
 * Non-mock stand-ins for the outbound ports, for tests that start the application under {@code prod} (03-inbound
 * backend §6, §8): prod has no mock notification sender or provisioning ({@code ProfileGuard} refuses them) and
 * {@code ProdAdapterCheck} refuses ports implemented only by {@code Mock*} beans, while no real adapter exists yet.
 * Active only with {@code prod} and {@code test.stub-adapters} set, so an accidentally scanned copy changes nothing
 * elsewhere: {@code new-ports} stubs only {@link NotificationSender} and {@link ProvisioningClient} (the ports without
 * an unconditional mock), {@code all} also the three 02 ports ({@code @Primary} over their mocks). They do nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(name = "test.stub-adapters")
public class StubProdAdapters {

    @Bean
    @Primary
    NotificationSender stubNotificationSender() {
        return new StubNotificationSender();
    }

    @Bean
    @Primary
    ProvisioningClient stubProvisioningClient() {
        return new StubProvisioningClient();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "test.stub-adapters", havingValue = "all")
    VerificationCodeSender stubVerificationCodeSender() {
        return (channel, destination, code) -> { };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "test.stub-adapters", havingValue = "all")
    PlanProvisioning stubPlanProvisioning() {
        return (msisdn, toPlanCode, effectiveDate) -> { };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "test.stub-adapters", havingValue = "all")
    PaymentGateway stubPaymentGateway() {
        return new StubPaymentGateway();
    }

    static final class StubPaymentGateway implements PaymentGateway {
        @Override
        public CheckoutSession createCheckout(String paymentRef, BigDecimal amount, String currency,
                                              String returnUrl) {
            throw new GatewayUnavailableException("stub payment gateway");
        }

        @Override
        public GatewayPaymentStatus getStatus(String paymentRef) {
            throw new GatewayUnavailableException("stub payment gateway");
        }

        @Override
        public GatewayEvent verifyWebhook(HttpHeaders headers, byte[] rawBody) {
            throw new WebhookPayloadException("stub payment gateway");
        }
    }

    static final class StubNotificationSender implements NotificationSender {
        @Override
        public void send(Channel channel, String destination, Template template, Map<String, String> params) {
            // delivers nothing
        }
    }

    static final class StubProvisioningClient implements ProvisioningClient {
        @Override
        public RestoreResult restore(String requestRef, List<String> msisdns, Instant until) {
            return new RestoreResult("STUB-" + requestRef);
        }

        @Override
        public void suspend(String requestRef, List<String> msisdns) {
            // provisions nothing
        }

        @Override
        public ProviderStatus getStatus(String requestRef) {
            return ProviderStatus.notFound();
        }
    }
}
