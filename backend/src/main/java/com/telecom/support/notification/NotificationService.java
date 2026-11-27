package com.telecom.support.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.notification.NotificationRepository.Due;
import com.telecom.support.notification.NotificationSender.Channel;
import com.telecom.support.notification.NotificationSender.Template;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer notification outbox (design 03-inbound backend R16, §6.1, §8; INB-04.7, NFR-AVAIL, NFR-PRIV).
 *
 * <p>{@link #enqueue} adds the rows in the caller's transaction (outbox: a notification exists exactly when the state
 * change that caused it committed): SMS to the primary MSISDN, EMAIL only to a verified email. {@link #dispatch} sends
 * one due row: it claims it with a guarded {@code PENDING → SENDING} update (auto-committed, so the claim is visible to
 * every other run before anything is sent), sends outside any transaction, then marks it SENT (destination masked) or
 * puts it back to PENDING with {@code attempts+1} and a 1 / 5 / 15 min backoff; the {@code app.notification.max-attempts}
 * th failure (default 3) makes it FAILED (destination masked) with a WARN. Logs never contain a destination, only the
 * row id, template, channel and source reference.
 */
@Service
public class NotificationService {

    /** The params key holding the source reference (e.g. a {@code REA} reference), stored as {@code source_ref}. */
    public static final String REFERENCE = "reference";

    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final TypeReference<TreeMap<String, String>> PARAMS = new TypeReference<>() {};

    /**
     * Where a notification may go: the customer's primary MSISDN and verified email, each null when there is none.
     */
    public record Contacts(String primaryMsisdn, String verifiedEmail) {

        /** Never print contact data. */
        @Override
        public String toString() {
            return "Contacts[sms=" + (primaryMsisdn != null) + ", email=" + (verifiedEmail != null) + "]";
        }
    }

    /** How one {@link #dispatch} ended. */
    enum Outcome { SENT, RETRY, FAILED, NOT_CLAIMED }

    private final NotificationRepository notifications;
    private final NotificationSender sender;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxAttempts;

    NotificationService(NotificationRepository notifications, NotificationSender sender, ObjectMapper objectMapper,
                        Clock clock, @Value("${app.notification.max-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.notification.max-attempts must be positive: " + maxAttempts);
        }
        this.notifications = notifications;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    /**
     * R16: queues {@code template} for the customer, one row per available channel, in the caller's transaction.
     * {@code params} must hold {@link #REFERENCE} (the source reference) and no personal data. A repeat of the same
     * (reference, template, channel) is ignored.
     *
     * @return the number of rows added (0–2)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueue(long customerId, Template template, Map<String, String> params, Contacts contacts) {
        String sourceRef = params.get(REFERENCE);
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("Notification params need a '" + REFERENCE + "'");
        }
        String json = toJson(params);
        Instant now = clock.instant();
        int added = 0;
        if (contacts.primaryMsisdn() != null) {
            added += notifications.insert(customerId, Channel.SMS, template, contacts.primaryMsisdn(), json, sourceRef,
                    now);
        }
        if (contacts.verifiedEmail() != null) {
            added += notifications.insert(customerId, Channel.EMAIL, template, contacts.verifiedEmail(), json,
                    sourceRef, now);
        }
        if (added == 0 && contacts.primaryMsisdn() == null && contacts.verifiedEmail() == null) {
            log.info("Notification {} for {}: customer has no destination", template, sourceRef);
        }
        return added;
    }

    /** Claims, sends and completes one due row (never inside a transaction). */
    Outcome dispatch(Due row) {
        Instant claimedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!notifications.claim(row.id(), claimedAt)) {
            return Outcome.NOT_CLAIMED; // another run (or instance) owns it
        }
        try {
            sender.send(row.channel(), row.destination(), row.template(), params(row.paramsJson()));
        } catch (RuntimeException e) { // DeliveryException, or any other failure of the port
            return failed(row, claimedAt, e);
        }
        if (!notifications.markSent(row.id(), claimedAt, mask(row.channel(), row.destination()), clock.instant())) {
            log.warn("Notification {} ({} {}, {}) was sent but its claim had been reset; it may be sent again",
                    row.id(), row.template(), row.channel(), row.sourceRef());
        }
        return Outcome.SENT;
    }

    /** SENDING rows claimed more than {@code staleAfter} ago become PENDING again (a run crashed mid-send). */
    int resetStale(Duration staleAfter) {
        Instant now = clock.instant();
        int reset = notifications.resetStale(now.minus(staleAfter), now);
        if (reset > 0) {
            log.warn("Notification dispatch: {} stale SENDING row(s) reset to PENDING", reset);
        }
        return reset;
    }

    private Outcome failed(Due row, Instant claimedAt, RuntimeException e) {
        String error = e.getClass().getSimpleName(); // the message may name the destination
        int attempt = row.attempts() + 1;
        Instant now = clock.instant();
        if (attempt >= maxAttempts) {
            notifications.markFailed(row.id(), claimedAt, mask(row.channel(), row.destination()), error, now);
            log.warn("Notification {} ({} {}, {}) FAILED after {} attempts ({})", row.id(), row.template(),
                    row.channel(), row.sourceRef(), attempt, error);
            return Outcome.FAILED;
        }
        Duration backoff = BACKOFF.get(Math.min(attempt, BACKOFF.size()) - 1);
        notifications.retryLater(row.id(), claimedAt, now.plus(backoff), error, now);
        log.info("Notification {} ({} {}, {}) attempt {} failed ({}); retried in {}", row.id(), row.template(),
                row.channel(), row.sourceRef(), attempt, error, backoff);
        return Outcome.RETRY;
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(params));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Notification params are not serialisable", e);
        }
    }

    private Map<String, String> params(String json) {
        try {
            return objectMapper.readValue(json, PARAMS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored notification params are not a JSON object of strings", e);
        }
    }

    /** §7 masking: MSISDN only its last 4 digits; email {@code ra***@ex***.com}. */
    static String mask(Channel channel, String value) {
        if (channel == Channel.SMS) {
            return value.length() <= 4 ? "****" : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
        }
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return "***";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String host = dot < 0 ? domain : domain.substring(0, dot);
        String tld = dot < 0 ? "" : domain.substring(dot);
        return prefix(local) + "***@" + prefix(host) + "***" + tld;
    }

    private static String prefix(String s) {
        return s.substring(0, Math.min(2, s.length()));
    }
}
