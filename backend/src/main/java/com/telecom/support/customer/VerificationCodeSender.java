package com.telecom.support.customer;

/**
 * Port that delivers a one-time contact verification code (design 02-customer backend §8, R3; CUS-02.2). Slice 02 ships
 * only {@link MockVerificationCodeSender}; the notification adapter replaces it in 03/04 (stated deviation from §14 #12,
 * OQ 11). Implementations must never log the code.
 */
public interface VerificationCodeSender {

    /** Contact channel of a verification; the values are the {@code contact_verifications.channel} values. */
    enum Channel { EMAIL, SECONDARY_MSISDN }

    /**
     * Sends {@code code} to {@code destination} (an email address or an E.164 number).
     *
     * @throws DeliveryException when the code could not be handed over (→ 503, row EXPIRED)
     */
    void send(Channel channel, String destination, String code);

    /** The code could not be delivered. The message never contains the code. */
    class DeliveryException extends RuntimeException {
        public DeliveryException(String message) {
            super(message);
        }
    }
}
