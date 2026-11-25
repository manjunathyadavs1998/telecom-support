package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Prod-only requirements of {@link ProfileGuard} added for slice 02: shared HMAC secrets (review C1; NFR-AVAIL, NFR-SEC)
 * and the payment return URL base (design 02-customer backend erratum E4). Outside prod none of them is required.
 */
class ProfileGuardTest {

    @Test
    void completeProdConfigurationPasses() {
        assertThatCode(() -> new ProfileGuard().postProcessEnvironment(prod(), null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.customer.otp.hmac-secret", "app.payment.gateway.webhook-secret",
            "app.payment.return-url-base"})
    void prodRefusesABlankValue(String property) {
        MockEnvironment blank = prod().withProperty(property, "  ");
        MockEnvironment empty = prod().withProperty(property, "");

        for (MockEnvironment env : new MockEnvironment[] {blank, empty}) {
            assertThatThrownBy(() -> new ProfileGuard().postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'" + property + "'")
                    .hasMessageContaining("'prod'");
        }
    }

    @Test
    void blankValuesAreAllowedOutsideProd() {
        MockEnvironment dev = new MockEnvironment()
                .withProperty("app.customer.otp.hmac-secret", "")
                .withProperty("app.payment.gateway.webhook-secret", "")
                .withProperty("app.payment.return-url-base", "");
        dev.setActiveProfiles("dev");

        assertThatCode(() -> new ProfileGuard().postProcessEnvironment(dev, null)).doesNotThrowAnyException();
    }

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.customer.otp.hmac-secret", "otp-secret")
                .withProperty("app.payment.gateway.webhook-secret", "webhook-secret")
                .withProperty("app.payment.return-url-base", "https://portal.example");
        env.setActiveProfiles("prod");
        return env;
    }
}
