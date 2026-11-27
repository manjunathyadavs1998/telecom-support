package com.telecom.support.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.telecom.support.customer.InboundTestClock;
import com.telecom.support.customer.VerifiedProbeController;
import com.telecom.support.notification.MockNotificationSender.Sent;
import com.telecom.support.notification.NotificationSender.Channel;
import com.telecom.support.notification.NotificationSender.Template;
import com.telecom.support.notification.NotificationService.Contacts;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B14: the notification outbox and {@link NotificationDispatchJob} (design 03-inbound backend R16, §8, §9; INB-04.7,
 * NFR-AVAIL, NFR-PRIV). The job has no endpoint, so there are no 401/403 cases; its schedule is disabled in tests and
 * each test calls a run. Time moves only through {@link InboundTestClock} (same context as the other 03 ITs). Each
 * test uses its own {@code REA-TN…} source references and asserts only on its own rows (other ITs may queue rows).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(VerifiedProbeController.class)
@TestPropertySource(properties = "logging.level.com.telecom.support=DEBUG")
class NotificationDispatchIT extends AbstractMySqlIT {

    private static final Instant T0 = InboundTestClock.T0;
    private static final String MSISDN = "+919811987654";
    private static final String EMAIL = "nina.notify@example.test";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.customer.InboundTestClock#clock")
    Clock clock;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    NotificationService notifications;

    @Autowired
    NotificationDispatchJob job;

    @Autowired
    MockNotificationSender sender;

    private long customerId;

    @BeforeEach
    void setUp() {
        InboundTestClock.CLOCK.set(T0);
        sender.reset();
        customerId = jdbc.queryForObject("SELECT MIN(id) FROM customers", Map.of(), Long.class);
    }

    @AfterEach
    void cleanUp() {
        InboundTestClock.CLOCK.set(T0);
        sender.reset();
        jdbc.update("DELETE FROM notifications WHERE source_ref LIKE 'REA-TN%'", Map.of());
    }

    @Test
    void queuedRowsAreSentOnceWithTheDestinationMaskedAfterwards(CapturedOutput output) {
        String ref = ref();

        assertThat(enqueue(ref, Template.REACTIVATION_STARTED, new Contacts(MSISDN, EMAIL))).isEqualTo(2);
        assertThat(enqueue(ref, Template.REACTIVATION_STARTED, new Contacts(MSISDN, EMAIL)))
                .as("same source_ref + template + channel").isZero();
        assertThat(enqueue(ref, Template.REACTIVATION_ENDED, new Contacts(MSISDN, null))).isEqualTo(1);
        assertThat(row(ref, Template.REACTIVATION_STARTED, Channel.SMS))
                .containsEntry("status", "PENDING").containsEntry("destination", MSISDN)
                .containsEntry("attempts", 0);

        job.run();

        assertThat(sentFor(ref)).extracting(Sent::channel, Sent::destination, Sent::template).containsExactlyInAnyOrder(
                tuple(Channel.SMS, MSISDN, Template.REACTIVATION_STARTED),
                tuple(Channel.EMAIL, EMAIL, Template.REACTIVATION_STARTED),
                tuple(Channel.SMS, MSISDN, Template.REACTIVATION_ENDED));
        assertThat(sentFor(ref).get(0).params()).containsEntry("reference", ref).containsEntry("until", "2030-01-16");
        Map<String, Object> sms = row(ref, Template.REACTIVATION_STARTED, Channel.SMS);
        assertThat(sms).containsEntry("status", "SENT").containsEntry("destination", "*********7654")
                .containsEntry("attempts", 0);
        assertThat(sms.get("sent_at")).isNotNull();
        assertThat(sms.get("claimed_at")).isNull();
        assertThat(row(ref, Template.REACTIVATION_STARTED, Channel.EMAIL))
                .containsEntry("status", "SENT").containsEntry("destination", "ni***@ex***.test");

        job.run();
        assertThat(sentFor(ref)).as("a SENT row is never sent again").hasSize(3);
        assertThat(output.getAll()).doesNotContain(MSISDN).doesNotContain(EMAIL);
    }

    @Test
    void failuresBackOffAndTheThirdMakesTheRowFailed(CapturedOutput output) {
        String ref = ref();
        enqueue(ref, Template.REACTIVATION_ENDED, new Contacts(MSISDN, null));
        sender.setFailing(true);

        job.run();
        Map<String, Object> first = row(ref, Template.REACTIVATION_ENDED, Channel.SMS);
        assertThat(first).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("destination", MSISDN).containsEntry("last_error", "DeliveryException");
        assertThat(first.get("next_attempt_at")).isEqualTo(utc(T0.plus(Duration.ofMinutes(1))));

        job.run(); // not due yet
        assertThat(row(ref, Template.REACTIVATION_ENDED, Channel.SMS)).containsEntry("attempts", 1);

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(1)));
        job.run();
        Map<String, Object> second = row(ref, Template.REACTIVATION_ENDED, Channel.SMS);
        assertThat(second).containsEntry("status", "PENDING").containsEntry("attempts", 2);
        assertThat(second.get("next_attempt_at")).isEqualTo(utc(T0.plus(Duration.ofMinutes(6))));

        InboundTestClock.CLOCK.set(T0.plus(Duration.ofMinutes(6)));
        job.run();
        Map<String, Object> failed = row(ref, Template.REACTIVATION_ENDED, Channel.SMS);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("destination", "*********7654").containsEntry("last_error", "DeliveryException");

        sender.setFailing(false);
        InboundTestClock.CLOCK.set(T0.plus(Duration.ofHours(1)));
        job.run();
        assertThat(sentFor(ref)).as("FAILED is final").isEmpty();
        assertThat(output.getAll()).contains("FAILED after 3 attempts").doesNotContain(MSISDN);
    }

    @Test
    void twoParallelRunsSendEveryRowExactlyOnce() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String ref = ref();
            refs.add(ref);
            enqueue(ref, Template.REACTIVATION_STARTED, new Contacts(MSISDN, EMAIL));
        }
        sender.setDelay(Duration.ofMillis(20));
        job.pageSize = 3; // several pages per run, so both runs walk the same rows

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<NotificationDispatchJob.RunResult>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return job.run();
                }));
            }
            start.countDown();
            for (Future<NotificationDispatchJob.RunResult> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            job.pageSize = NotificationDispatchJob.PAGE_SIZE;
            pool.shutdownNow();
        }

        for (String ref : refs) {
            assertThat(sentFor(ref)).extracting(Sent::channel).as(ref)
                    .containsExactlyInAnyOrder(Channel.SMS, Channel.EMAIL);
            assertThat(row(ref, Template.REACTIVATION_STARTED, Channel.SMS)).containsEntry("status", "SENT");
            assertThat(row(ref, Template.REACTIVATION_STARTED, Channel.EMAIL)).containsEntry("status", "SENT");
        }
    }

    @Test
    void sendingLeftByACrashedRunIsResetAfterFiveMinutes() {
        String stale = ref();
        String recent = ref();
        enqueue(stale, Template.REACTIVATION_STARTED, new Contacts(MSISDN, null));
        enqueue(recent, Template.REACTIVATION_STARTED, new Contacts(MSISDN, null));
        claimAt(stale, T0.minus(Duration.ofMinutes(5)).minusSeconds(1));
        claimAt(recent, T0.minus(Duration.ofMinutes(4)));

        NotificationDispatchJob.RunResult result = job.run();

        assertThat(result.reset()).isGreaterThanOrEqualTo(1);
        assertThat(sentFor(stale)).hasSize(1);
        assertThat(row(stale, Template.REACTIVATION_STARTED, Channel.SMS)).containsEntry("status", "SENT");
        assertThat(sentFor(recent)).isEmpty();
        assertThat(row(recent, Template.REACTIVATION_STARTED, Channel.SMS)).containsEntry("status", "SENDING");
    }

    @Test
    void enqueueNeedsTheCallersTransactionAndAReference() {
        String ref = ref();
        assertThatThrownBy(() -> notifications.enqueue(customerId, Template.REACTIVATION_STARTED,
                Map.of("reference", ref), new Contacts(MSISDN, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> notifications.enqueue(customerId,
                Template.REACTIVATION_STARTED, Map.of("until", "x"), new Contacts(MSISDN, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(enqueue(ref, Template.REACTIVATION_STARTED, new Contacts(null, null))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE source_ref = :ref",
                Map.of("ref", ref), Integer.class)).isZero();
    }

    @Test
    void masking() {
        assertThat(NotificationService.mask(Channel.SMS, "+919811987654")).isEqualTo("*********7654");
        assertThat(NotificationService.mask(Channel.SMS, "123")).isEqualTo("****");
        assertThat(NotificationService.mask(Channel.EMAIL, "ravi@example.com")).isEqualTo("ra***@ex***.com");
        assertThat(NotificationService.mask(Channel.EMAIL, "no-at-sign")).isEqualTo("***");
    }

    // ------------------------------------------------------------------ helpers

    private static String ref() {
        return "REA-TN" + System.nanoTime() % 1_000_000 + "-" + SEQ.incrementAndGet();
    }

    private int enqueue(String ref, Template template, Contacts contacts) {
        return tx.execute(s -> notifications.enqueue(customerId, template,
                Map.of("reference", ref, "until", "2030-01-16"), contacts));
    }

    private List<Sent> sentFor(String ref) {
        return sender.sent().stream().filter(s -> ref.equals(s.params().get("reference"))).toList();
    }

    private Map<String, Object> row(String ref, Template template, Channel channel) {
        return jdbc.queryForMap("""
                SELECT status, destination, attempts, next_attempt_at, claimed_at, last_error, sent_at
                FROM notifications WHERE source_ref = :ref AND template = :template AND channel = :channel
                """, Map.of("ref", ref, "template", template.name(), "channel", channel.name()));
    }

    private void claimAt(String ref, Instant claimedAt) {
        jdbc.update("UPDATE notifications SET status = 'SENDING', claimed_at = :at WHERE source_ref = :ref",
                Map.of("ref", ref, "at", utc(claimedAt)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
