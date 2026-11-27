package com.telecom.support.customer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * One-time code generation, HMAC and constant-time comparison (design 03-inbound backend §6, R3; extracted unchanged
 * from 02's {@code ContactVerificationService}, 02 R3, §3.2). Shared by contact verification (02) and the agent
 * identity check OTP (03), so both store codes the same way under the same key ({@code app.customer.otp.*}).
 *
 * <p>Codes are 6 digits ({@code dev-fixed-code} instead, dev only); only the hex HMAC-SHA256 is stored. The code is
 * never logged.
 */
@Component
@EnableConfigurationProperties(OtpProperties.class)
class OtpCodec {

    private static final Logger log = LoggerFactory.getLogger(OtpCodec.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String devFixedCode;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    OtpCodec(OtpProperties otp) {
        this.devFixedCode = otp.devFixedCode();
        byte[] key = new byte[32];
        if (otp.hmacSecret() != null) {
            key = otp.hmacSecret().getBytes(StandardCharsets.UTF_8);
        } else {
            random.nextBytes(key);
            log.info("app.customer.otp.hmac-secret is not set: using a random per-process key");
        }
        this.hmacKey = new SecretKeySpec(key, HMAC_ALGORITHM);
    }

    /** A new 6-digit code (leading zeros kept), or the configured dev fixed code. */
    String newCode() {
        return devFixedCode != null ? devFixedCode : String.format("%06d", random.nextInt(1_000_000));
    }

    /** Hex HMAC-SHA256 of the code (64 characters, the {@code code_hmac} columns). */
    String hmac(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Whether {@code code} matches the stored {@code codeHmac}; constant time over the HMACs (02 §3.2 step 2). */
    boolean matches(String code, String codeHmac) {
        return MessageDigest.isEqual(hmac(code).getBytes(StandardCharsets.US_ASCII),
                codeHmac.getBytes(StandardCharsets.US_ASCII));
    }
}
