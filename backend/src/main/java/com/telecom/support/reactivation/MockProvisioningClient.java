package com.telecom.support.reactivation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link ProvisioningClient} (design 03-inbound backend §8, §3.1; INB-04.4/04.5, NFR-AVAIL), keyed and
 * idempotent by request reference. Registered only with {@code app.reactivation.provisioning.mock.enabled=true} (dev
 * and tests); {@code ProfileGuard} refuses that with {@code prod}. Modes ({@code app.reactivation.provisioning.mock.mode},
 * switchable by tests):
 * <ul>
 *   <li>{@link Mode#NORMAL}: {@code restore} records RESTORED (restored at the clock's now, a provider ref), a repeat
 *       returns the same provider ref; {@code suspend} records SUSPENDED; {@code getStatus} answers from the records
 *       ({@code NOT_FOUND} when none).</li>
 *   <li>{@link Mode#REJECT}: {@code restore} is refused ({@link ProvisioningRejectedException}); other calls normal.</li>
 *   <li>{@link Mode#UNAVAILABLE}: every call is refused ({@link ProvisioningUnavailableException}) and changes nothing.</li>
 *   <li>{@link Mode#TIMEOUT} (outcome unknown): {@code restore} and {@code suspend} take effect first (the provider got
 *       them), then every call waits for the timeout delay and throws {@link ProvisioningTimeoutException};
 *       {@code getStatus} is unanswered the same way.</li>
 *   <li>{@link Mode#SUSPEND_FAIL}: {@code suspend} fails ({@link ProvisioningUnavailableException}); other calls
 *       normal.</li>
 * </ul>
 * The timeout delay is {@code app.reactivation.provisioning.timeout} (tests may shorten it). Calls are recorded (bounded,
 * {@link #MAX_RECORDED}); nothing is logged, so no MSISDN reaches a log.
 */
@Component
@ConditionalOnProperty(name = "app.reactivation.provisioning.mock.enabled", havingValue = "true")
@EnableConfigurationProperties(ReactivationProperties.class)
public class MockProvisioningClient implements ProvisioningClient {

    /** Behaviour of the following calls. */
    public enum Mode { NORMAL, REJECT, UNAVAILABLE, TIMEOUT, SUSPEND_FAIL }

    /** {@link Call#operation()} values. */
    public enum Operation { RESTORE, SUSPEND, GET_STATUS }

    /** One call, whatever its outcome. */
    public record Call(Operation operation, String requestRef, List<String> msisdns, Instant until) {}

    static final int MAX_RECORDED = 1_000;
    static final int MAX_REQUESTS = 10_000;

    private record Entry(State state, Instant restoredAt, String providerRef) {}

    private final Clock clock;
    private final Duration timeout;
    private final Mode initialMode;
    private final Map<String, Entry> requests = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_REQUESTS;
        }
    };
    private final Deque<Call> calls = new ArrayDeque<>();
    private volatile Mode mode;
    private volatile Duration timeoutDelay;

    MockProvisioningClient(ReactivationProperties properties, Clock clock) {
        this.clock = clock;
        this.timeout = properties.provisioning().timeout();
        this.initialMode = properties.provisioning().mock().mode();
        this.mode = initialMode;
        this.timeoutDelay = timeout;
    }

    @Override
    public RestoreResult restore(String requestRef, List<String> msisdns, Instant until) {
        Mode current = mode;
        record(new Call(Operation.RESTORE, requestRef, List.copyOf(msisdns), until));
        if (current == Mode.UNAVAILABLE) {
            throw new ProvisioningUnavailableException("Mock provisioning refused restore for " + requestRef);
        }
        if (current == Mode.REJECT) {
            throw new ProvisioningRejectedException("Mock provisioning rejected restore for " + requestRef);
        }
        Entry entry;
        synchronized (requests) {
            entry = requests.get(requestRef);
            if (entry == null) {
                entry = new Entry(State.RESTORED, clock.instant(), "PRV-" + requestRef);
                requests.put(requestRef, entry);
            }
        }
        if (current == Mode.TIMEOUT) {
            timeOut("restore", requestRef);
        }
        return new RestoreResult(entry.providerRef());
    }

    @Override
    public void suspend(String requestRef, List<String> msisdns) {
        Mode current = mode;
        record(new Call(Operation.SUSPEND, requestRef, List.copyOf(msisdns), null));
        if (current == Mode.UNAVAILABLE || current == Mode.SUSPEND_FAIL) {
            throw new ProvisioningUnavailableException("Mock provisioning refused suspend for " + requestRef);
        }
        synchronized (requests) {
            Entry entry = requests.get(requestRef);
            requests.put(requestRef, new Entry(State.SUSPENDED, null, entry == null ? null : entry.providerRef()));
        }
        if (current == Mode.TIMEOUT) {
            timeOut("suspend", requestRef);
        }
    }

    @Override
    public ProviderStatus getStatus(String requestRef) {
        Mode current = mode;
        record(new Call(Operation.GET_STATUS, requestRef, List.of(), null));
        if (current == Mode.UNAVAILABLE) {
            throw new ProvisioningUnavailableException("Mock provisioning refused getStatus for " + requestRef);
        }
        if (current == Mode.TIMEOUT) {
            timeOut("getStatus", requestRef);
        }
        Entry entry;
        synchronized (requests) {
            entry = requests.get(requestRef);
        }
        if (entry == null) {
            return ProviderStatus.notFound();
        }
        return entry.state() == State.RESTORED
                ? ProviderStatus.restored(entry.restoredAt(), entry.providerRef())
                : ProviderStatus.suspended();
    }

    // ------------------------------------------------------------------ tests

    public Mode mode() {
        return mode;
    }

    /** Switches the behaviour of following calls (tests). */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NORMAL : mode;
    }

    /** Shortens (or restores) the wait of {@link Mode#TIMEOUT} calls (tests). */
    public void setTimeoutDelay(Duration delay) {
        this.timeoutDelay = delay == null ? timeout : delay;
    }

    /** Records what the provider knows about {@code requestRef}, as if it had happened at {@code restoredAt} (tests). */
    public void putStatus(String requestRef, State state, Instant restoredAt) {
        synchronized (requests) {
            if (state == State.NOT_FOUND) {
                requests.remove(requestRef);
            } else {
                requests.put(requestRef, new Entry(state, state == State.RESTORED ? restoredAt : null,
                        "PRV-" + requestRef));
            }
        }
    }

    /** The provider's record of {@code requestRef} (tests). */
    public Optional<ProviderStatus> statusOf(String requestRef) {
        synchronized (requests) {
            Entry entry = requests.get(requestRef);
            return Optional.ofNullable(entry).map(e -> new ProviderStatus(e.state(), e.restoredAt(), e.providerRef()));
        }
    }

    /** Recorded calls, oldest first (tests). */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Recorded calls for {@code requestRef}, oldest first (tests). */
    public List<Call> callsFor(String requestRef) {
        return calls().stream().filter(c -> c.requestRef().equals(requestRef)).toList();
    }

    /** Forgets every request and call, returns to the configured mode and the configured timeout delay (tests). */
    public void reset() {
        mode = initialMode;
        timeoutDelay = timeout;
        synchronized (requests) {
            requests.clear();
        }
        synchronized (calls) {
            calls.clear();
        }
    }

    private void record(Call call) {
        synchronized (calls) {
            if (calls.size() == MAX_RECORDED) {
                calls.removeFirst();
            }
            calls.addLast(call);
        }
    }

    private void timeOut(String call, String requestRef) {
        try {
            Thread.sleep(timeoutDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new ProvisioningTimeoutException("Mock provisioning " + call + " for " + requestRef + " timed out after "
                + timeout);
    }
}
