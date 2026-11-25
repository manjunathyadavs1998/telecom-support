package com.telecom.support.payment;

import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import java.time.Clock;
import java.time.Instant;

/**
 * The one mutable test clock of the payment webhook and reconciliation ITs. Both reference {@link #clock()} as their
 * {@code @TestBean} factory, so they share one cached Spring context instead of adding two.
 */
final class PaymentTestClock {

    static final Instant T0 = Instant.parse("2026-06-01T06:00:00Z");
    static final MutableClock CLOCK = new MutableClock(T0);

    private PaymentTestClock() {
    }

    static Clock clock() {
        return CLOCK;
    }
}
