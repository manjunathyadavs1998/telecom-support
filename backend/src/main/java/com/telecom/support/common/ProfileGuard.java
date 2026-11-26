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
 * The {@code e2e} profile refuses to start without {@code dev}: it wipes the database at startup, which is only safe
 * against the local dev database (review C20, erratum E5). The {@code prod} profile refuses to start when
 * <ul>
 *   <li>{@code e2e} is also active: it relaxes the L1 login limit (design 01-foundation backend §12 E2.5) and wipes the
 *       database at startup (design 02-customer backend erratum E5);</li>
 *   <li>{@code spring.flyway.clean-disabled} is false: Flyway clean would drop every table (erratum E5);</li>
 *   <li>{@code app.customer.otp.dev-fixed-code} is set: every contact verification code would be known
 *       (design 02-customer backend §6, §8, OQ 11);</li>
 *   <li>{@code app.payment.mock.enabled} is true: the public mock checkout page would let anyone settle a payment
 *       (design 02-customer backend §4, §6);</li>
 *   <li>{@code app.customer.otp.hmac-secret} or {@code app.payment.gateway.webhook-secret} is blank: each instance would
 *       make its own random key, so codes and signed webhooks fail across instances (review C1; NFR-AVAIL, NFR-SEC);</li>
 *   <li>{@code app.payment.return-url-base} is blank: E15 builds the gateway return URL from it (erratum E4);</li>
 *   <li>{@code app.payment.reconcile.pending-cron} or {@code app.payment.reconcile.late-capture-cron} is {@code -}
 *       (disabled): PENDING payments would never reach REVIEW and late captures would never be applied (02 backend
 *       §3.1, review C18; NFR-REL). Their defaults are every 60 s and every 15 min.</li>
 * </ul>
 */
public class ProfileGuard implements EnvironmentPostProcessor, Ordered {

    static final String OTP_DEV_FIXED_CODE = "app.customer.otp.dev-fixed-code";
    static final String PAYMENT_MOCK_ENABLED = "app.payment.mock.enabled";
    static final String OTP_HMAC_SECRET = "app.customer.otp.hmac-secret";
    static final String PAYMENT_WEBHOOK_SECRET = "app.payment.gateway.webhook-secret";
    static final String PAYMENT_RETURN_URL_BASE = "app.payment.return-url-base";
    static final String FLYWAY_CLEAN_DISABLED = "spring.flyway.clean-disabled";
    static final String RECONCILE_PENDING_CRON = "app.payment.reconcile.pending-cron";
    static final String RECONCILE_LATE_CAPTURE_CRON = "app.payment.reconcile.late-capture-cron";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        boolean e2e = environment.acceptsProfiles(Profiles.of("e2e"));
        if (prod && e2e) {
            throw new IllegalStateException("Profiles 'prod' and 'e2e' must not be active together: 'e2e' relaxes the "
                    + "login rate limit, wipes the database at startup and is for local end-to-end tests only "
                    + "(01 backend §12 E2.5, 02 backend erratum E5)");
        }
        if (e2e && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("Profile 'e2e' must be active together with 'dev': it wipes the database "
                    + "at startup, which is only safe against the local dev database (02 backend erratum E5)");
        }
        if (!prod) {
            return;
        }
        if (Boolean.FALSE.equals(environment.getProperty(FLYWAY_CLEAN_DISABLED, Boolean.class, Boolean.TRUE))) {
            throw new IllegalStateException("Property '" + FLYWAY_CLEAN_DISABLED + "' must not be false with the "
                    + "'prod' profile: Flyway clean drops every table (02 backend erratum E5)");
        }
        if (StringUtils.hasText(environment.getProperty(OTP_DEV_FIXED_CODE))) {
            throw new IllegalStateException("Property '" + OTP_DEV_FIXED_CODE + "' must not be set with the 'prod' "
                    + "profile: it makes every contact verification code known (02 backend §8, OQ 11)");
        }
        if (Boolean.TRUE.equals(environment.getProperty(PAYMENT_MOCK_ENABLED, Boolean.class, Boolean.FALSE))) {
            throw new IllegalStateException("Property '" + PAYMENT_MOCK_ENABLED + "' must not be true with the 'prod' "
                    + "profile: the mock checkout page is public and settles payments (02 backend §4, §6)");
        }
        for (String secret : new String[] {OTP_HMAC_SECRET, PAYMENT_WEBHOOK_SECRET}) {
            if (!StringUtils.hasText(environment.getProperty(secret))) {
                throw new IllegalStateException("Property '" + secret + "' must be set with the 'prod' profile: a "
                        + "blank value makes a random key per instance, which other instances cannot verify");
            }
        }
        if (!StringUtils.hasText(environment.getProperty(PAYMENT_RETURN_URL_BASE))) {
            throw new IllegalStateException("Property '" + PAYMENT_RETURN_URL_BASE + "' must be set with the 'prod' "
                    + "profile: it is the SPA origin the payment gateway returns to (02 backend erratum E4)");
        }
        for (String cron : new String[] {RECONCILE_PENDING_CRON, RECONCILE_LATE_CAPTURE_CRON}) {
            if ("-".equals(environment.getProperty(cron, "").trim())) {
                throw new IllegalStateException("Property '" + cron + "' must not be '-' (disabled) with the 'prod' "
                        + "profile: payment reconciliation must run (02 backend §3.1)");
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
