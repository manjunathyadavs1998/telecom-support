package com.telecom.support.customer;

import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import java.time.Clock;
import java.time.Instant;

/**
 * The one mutable test clock of the 03 inbound customer ITs. Each references {@link #clock()} as its {@code @TestBean}
 * factory (with the same DEBUG logging property), so they share one cached Spring context. T0 lies after every
 * test-data row (written at the real time), so seeded identity checks are never valid at test time.
 */
final class InboundTestClock {

    static final Instant T0 = Instant.parse("2030-01-15T06:00:00Z");
    static final MutableClock CLOCK = new MutableClock(T0);

    private InboundTestClock() {
    }

    static Clock clock() {
        return CLOCK;
    }
}
