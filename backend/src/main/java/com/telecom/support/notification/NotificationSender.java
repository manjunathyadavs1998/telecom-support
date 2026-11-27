package com.telecom.support.notification;

import java.util.Map;

/**
 * Port that delivers one customer notification (design 03-inbound backend §8, R16; INB-04.7). Slice 03 ships only
 * {@link MockNotificationSender} (stated deviation from §14 #12, OQ 9). Implementations must never log the destination
 * unmasked; the params carry no personal data (reference and times only).
 */
public interface NotificationSender {

    /** {@code notifications.channel}. */
    enum Channel { SMS, EMAIL }

    /** {@code notifications.template}. */
    enum Template { REACTIVATION_STARTED, REACTIVATION_ENDED }

    /**
     * Sends {@code template} with {@code params} to {@code destination} (an E.164 number for SMS, an email address for
     * EMAIL).
     *
     * @throws DeliveryException when the notification could not be handed over (retried with backoff, R16)
     */
    void send(Channel channel, String destination, Template template, Map<String, String> params);

    /** The notification could not be delivered. The message never contains the destination. */
    class DeliveryException extends RuntimeException {
        public DeliveryException(String message) {
            super(message);
        }
    }
}
