package com.telecom.support.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.customer.ContactVerificationController.ContactVerificationStart;
import com.telecom.support.customer.VerificationCodeSender.Channel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * B6: {@link OtpCodec} extracted from 02's contact verification (design 03-inbound backend §6, R3; 02 R3, §3.2) and the
 * {@code PRIMARY_MSISDN} sender channel (03 §8), which the 02 contact verification API never accepts.
 */
class OtpCodecTest {

    private static OtpCodec codec(String secret, String devFixedCode) {
        return new OtpCodec(new OtpProperties(null, null, null, secret, devFixedCode));
    }

    @Test
    void codesAreSixDigitsWithLeadingZerosKept() {
        OtpCodec codec = codec("secret", null);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = codec.newCode();
            assertThat(code).matches("\\d{6}");
            codes.add(code);
        }
        assertThat(codes).hasSizeGreaterThan(150); // random, not fixed
    }

    @Test
    void devFixedCodeReplacesTheRandomCode() {
        OtpCodec codec = codec("secret", "123456");
        assertThat(codec.newCode()).isEqualTo("123456");
        assertThat(codec.newCode()).isEqualTo("123456");
    }

    @Test
    void hmacIsHexSha256KeyedByTheSecret() {
        OtpCodec a = codec("secret", null);
        String hmac = a.hmac("123456");

        assertThat(hmac).matches("[0-9a-f]{64}").doesNotContain("123456");
        assertThat(codec("secret", null).hmac("123456")).isEqualTo(hmac);
        assertThat(codec("other-secret", null).hmac("123456")).isNotEqualTo(hmac);
        assertThat(a.hmac("123457")).isNotEqualTo(hmac);
    }

    @Test
    void withoutASecretEachCodecHasItsOwnRandomKey() {
        assertThat(codec(null, null).hmac("123456")).isNotEqualTo(codec(null, null).hmac("123456"));
        assertThat(codec(" ", null).hmac("123456")).isNotEqualTo(codec(" ", null).hmac("123456"));
    }

    @Test
    void matchesComparesAgainstTheStoredHmac() {
        OtpCodec codec = codec("secret", null);
        String stored = codec.hmac("042042");

        assertThat(codec.matches("042042", stored)).isTrue();
        assertThat(codec.matches("042043", stored)).isFalse();
        assertThat(codec.matches("42042", stored)).isFalse();
        assertThat(codec("other-secret", null).matches("042042", stored)).isFalse();
    }

    // ---------------------------------------------------------------- PRIMARY_MSISDN sender channel

    @Test
    void mockSenderAcceptsThePrimaryMsisdnChannel() {
        MockVerificationCodeSender sender = new MockVerificationCodeSender();
        sender.send(Channel.PRIMARY_MSISDN, "+919900000006", "654321");
        assertThat(sender.lastCode("+919900000006")).contains("654321");
    }

    @Test
    void contactVerificationRejectsThePrimaryMsisdnChannel() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<String> invalid = paths(validator.validate(
                    new ContactVerificationStart(Channel.PRIMARY_MSISDN, "+919811111111")));
            assertThat(invalid).containsExactly("channel");

            assertThat(validator.validate(new ContactVerificationStart(Channel.SECONDARY_MSISDN, "+919811111111")))
                    .isEmpty();
            assertThat(validator.validate(new ContactVerificationStart(Channel.EMAIL, "a@example.test"))).isEmpty();
        }
    }

    private static Set<String> paths(Set<ConstraintViolation<ContactVerificationStart>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
