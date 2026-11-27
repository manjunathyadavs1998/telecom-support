package com.telecom.support.notification;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link NotificationSender} (design 03-inbound backend §8, §6; OQ 9): nothing is delivered; each send is
 * kept (bounded, {@link #MAX_RECORDED}) for tests and INFO-logged with the destination masked. Registered only with
 * {@code app.notification.mock.enabled=true} (dev and tests); {@code ProfileGuard} refuses that with {@code prod}.
 */
@Component
@ConditionalOnProperty(name = "app.notification.mock.enabled", havingValue = "true")
public class MockNotificationSender implements NotificationSender {

    static final int MAX_RECORDED = 1_000;

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    /** One handed-over notification. */
    public record Sent(Channel channel, String destination, Template template, Map<String, String> params) {}

    private final Deque<Sent> sent = new ArrayDeque<>();
    private volatile boolean failing;
    private volatile Duration delay = Duration.ZERO;

    @Override
    public void send(Channel channel, String destination, Template template, Map<String, String> params) {
        Duration wait = delay;
        if (!wait.isZero()) {
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failing) {
            throw new DeliveryException("Mock notification sender is set to fail (" + channel + ")");
        }
        synchronized (sent) {
            if (sent.size() == MAX_RECORDED) {
                sent.removeFirst();
            }
            sent.addLast(new Sent(channel, destination, template, Map.copyOf(params)));
        }
        log.info("Mock notification {} via {} to {} (reference {})", template, channel,
                NotificationService.mask(channel, destination), params.get(NotificationService.REFERENCE));
    }

    /** Handed-over notifications, oldest first (tests). */
    public List<Sent> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Makes every following {@link #send} throw {@link DeliveryException} until reset (tests). */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /** Delays every following {@link #send} (tests: widens the window of parallel dispatch runs). */
    public void setDelay(Duration delay) {
        this.delay = delay == null ? Duration.ZERO : delay;
    }

    /** Forgets recorded sends, stops failing and removes the delay (tests). */
    public void reset() {
        failing = false;
        delay = Duration.ZERO;
        synchronized (sent) {
            sent.clear();
        }
    }
}
