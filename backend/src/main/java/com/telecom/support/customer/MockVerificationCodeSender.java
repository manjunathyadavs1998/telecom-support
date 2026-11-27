package com.telecom.support.customer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link VerificationCodeSender} (design 02-customer backend §8, OQ 11): nothing is delivered; the last code per
 * destination is kept so tests can read it, for every channel (incl. 03's {@code PRIMARY_MSISDN}). In dev the optional fixed code ({@code app.customer.otp.dev-fixed-code},
 * which {@code ProfileGuard} refuses with the {@code prod} profile) makes the code known without reading it here.
 * Nothing is logged, so the code never reaches a log. The map is bounded ({@link #MAX_DESTINATIONS}, oldest evicted).
 */
@Component
public class MockVerificationCodeSender implements VerificationCodeSender {

    static final int MAX_DESTINATIONS = 10_000;

    private final Map<String, String> lastCodes = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_DESTINATIONS;
        }
    };
    private volatile boolean failing;

    @Override
    public void send(Channel channel, String destination, String code) {
        if (failing) {
            throw new DeliveryException("Mock verification code sender is set to fail (" + channel + ")");
        }
        synchronized (lastCodes) {
            lastCodes.remove(destination); // re-insert so the most recent destination is evicted last
            lastCodes.put(destination, code);
        }
    }

    /** The last code sent to {@code destination} (tests). */
    public Optional<String> lastCode(String destination) {
        synchronized (lastCodes) {
            return Optional.ofNullable(lastCodes.get(destination));
        }
    }

    /** Makes every following {@link #send} throw {@link DeliveryException} until reset (tests: sender down → 503). */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /** Forgets every code (tests). */
    public void clear() {
        synchronized (lastCodes) {
            lastCodes.clear();
        }
    }

    int size() {
        synchronized (lastCodes) {
            return lastCodes.size();
        }
    }
}
