package com.telecom.support.customer;

/**
 * Port that delivers a one-time contact verification code (design 02-customer backend §8, R3; CUS-02.2) and, since 03,
 * the agent identity check code to the customer's primary MSISDN (03-inbound backend R3, §8; INB-01.2). Slice 02 ships
 * only {@link MockVerificationCodeSender}; the notification adapter replaces it in 03/04 (stated deviation from §14 #12,
 * OQ 11). Implementations must never log the code.
 */
public interface VerificationCodeSender {

    /**
     * Destination channel of a code. {@code EMAIL} and {@code SECONDARY_MSISDN} are the {@code contact_verifications.channel}
     * values (02 contact verification); {@code PRIMARY_MSISDN} is used only by the 03 identity check OTP and is never
     * accepted by the contact verification API.
     */
    enum Channel { EMAIL, SECONDARY_MSISDN, PRIMARY_MSISDN }

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
