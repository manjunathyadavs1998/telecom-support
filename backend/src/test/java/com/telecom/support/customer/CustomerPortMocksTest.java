package com.telecom.support.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.common.ProfileGuard;
import com.telecom.support.customer.MockPlanProvisioning.Applied;
import com.telecom.support.customer.MockPlanProvisioning.Mode;
import com.telecom.support.customer.PlanProvisioning.ProvisioningException;
import com.telecom.support.customer.VerificationCodeSender.Channel;
import com.telecom.support.customer.VerificationCodeSender.DeliveryException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** B5: the customer ports' mocks (design 02-customer backend §8, OQ 11; CUS-02.2, BR-08, NFR-SEC). */
@ExtendWith(OutputCaptureExtension.class)
class CustomerPortMocksTest {

    // ---------------------------------------------------------------- VerificationCodeSender

    @Test
    void senderKeepsTheLastCodePerDestination() {
        MockVerificationCodeSender sender = new MockVerificationCodeSender();

        sender.send(Channel.EMAIL, "a@example.test", "111111");
        sender.send(Channel.SECONDARY_MSISDN, "+919811111111", "222222");
        sender.send(Channel.EMAIL, "a@example.test", "333333");

        assertThat(sender.lastCode("a@example.test")).contains("333333");
        assertThat(sender.lastCode("+919811111111")).contains("222222");
        assertThat(sender.lastCode("b@example.test")).isEmpty();

        sender.clear();
        assertThat(sender.lastCode("a@example.test")).isEmpty();
    }

    @Test
    void senderFailureModeThrowsWithoutTheCodeAndStoresNothing() {
        MockVerificationCodeSender sender = new MockVerificationCodeSender();
        sender.setFailing(true);

        assertThatThrownBy(() -> sender.send(Channel.EMAIL, "a@example.test", "987654"))
                .isInstanceOf(DeliveryException.class)
                .message().doesNotContain("987654");
        assertThat(sender.lastCode("a@example.test")).isEmpty();

        sender.setFailing(false);
        sender.send(Channel.EMAIL, "a@example.test", "987654");
        assertThat(sender.lastCode("a@example.test")).contains("987654");
    }

    @Test
    void senderMemoryIsBoundedAndEvictsTheOldestDestination() {
        MockVerificationCodeSender sender = new MockVerificationCodeSender();
        for (int i = 0; i <= MockVerificationCodeSender.MAX_DESTINATIONS; i++) {
            sender.send(Channel.EMAIL, "u" + i + "@example.test", "000000");
        }

        assertThat(sender.size()).isEqualTo(MockVerificationCodeSender.MAX_DESTINATIONS);
        assertThat(sender.lastCode("u0@example.test")).isEmpty();
        assertThat(sender.lastCode("u1@example.test")).contains("000000");
    }

    @Test
    void senderNeverLogsTheCode(CapturedOutput output) {
        MockVerificationCodeSender sender = new MockVerificationCodeSender();
        sender.send(Channel.EMAIL, "a@example.test", "424242");
        sender.setFailing(true);
        assertThatThrownBy(() -> sender.send(Channel.EMAIL, "a@example.test", "434343"));

        assertThat(output.getAll()).doesNotContain("424242").doesNotContain("434343");
    }

    // ---------------------------------------------------------------- dev fixed code vs prod (OQ 11)

    @Test
    void prodRefusesToStartWithADevFixedCode() {
        MockEnvironment env = new MockEnvironment().withProperty("app.customer.otp.dev-fixed-code", "123456");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProfileGuard().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.customer.otp.dev-fixed-code")
                .hasMessageNotContaining("123456");
    }

    @Test
    void devFixedCodeIsAllowedOutsideProdAndProdWithoutItStarts() {
        MockEnvironment dev = new MockEnvironment().withProperty("app.customer.otp.dev-fixed-code", "123456");
        dev.setActiveProfiles("dev");
        MockEnvironment prod = new MockEnvironment().withProperty("app.customer.otp.dev-fixed-code", "")
                // the other prod requirements of the guard (review C1, erratum E4)
                .withProperty("app.customer.otp.hmac-secret", "otp-secret")
                .withProperty("app.payment.gateway.webhook-secret", "webhook-secret")
                .withProperty("app.payment.return-url-base", "https://portal.example");
        prod.setActiveProfiles("prod");

        assertThatCode(() -> new ProfileGuard().postProcessEnvironment(dev, null)).doesNotThrowAnyException();
        assertThatCode(() -> new ProfileGuard().postProcessEnvironment(prod, null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- PlanProvisioning

    @Test
    void provisioningSucceedsAndRecordsTheCall() {
        MockPlanProvisioning provisioning = new MockPlanProvisioning();
        LocalDate effective = LocalDate.of(2026, 12, 5);

        provisioning.apply("+919900000001", "PRE_449", effective);

        assertThat(provisioning.mode()).isEqualTo(Mode.NORMAL);
        assertThat(provisioning.applied()).containsExactly(new Applied("+919900000001", "PRE_449", effective));
    }

    @Test
    void provisioningFailModeThrowsAndRecordsNothing() {
        MockPlanProvisioning provisioning = new MockPlanProvisioning();
        provisioning.setMode(Mode.FAIL);

        assertThatThrownBy(() -> provisioning.apply("+919900000001", "PRE_449", LocalDate.of(2026, 12, 5)))
                .isInstanceOf(ProvisioningException.class);
        assertThat(provisioning.applied()).isEmpty();

        provisioning.reset();
        assertThat(provisioning.mode()).isEqualTo(Mode.NORMAL);
        provisioning.apply("+919900000001", "PRE_449", LocalDate.of(2026, 12, 5));
        assertThat(provisioning.applied()).hasSize(1);
    }

    @Test
    void provisioningRecordsAreBounded() {
        MockPlanProvisioning provisioning = new MockPlanProvisioning();
        for (int i = 0; i <= MockPlanProvisioning.MAX_RECORDED; i++) {
            provisioning.apply("+91990000" + String.format("%04d", i), "PRE_449", LocalDate.of(2026, 12, 5));
        }

        assertThat(provisioning.applied()).hasSize(MockPlanProvisioning.MAX_RECORDED);
        assertThat(provisioning.applied().get(0).msisdn()).isEqualTo("+919900000001");
    }
}
