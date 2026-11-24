package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.common.reference.ReferenceGenerator;
import com.telecom.support.support.AbstractMySqlIT;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B6: reference IDs (00-overview §8, 01 backend §6.2/§8; BR-01, XC-05). Time is the mutable fixed clock from
 * {@link IdempotencyServiceIT}; the day is the Asia/Kolkata date, so 18:30Z is already the next day.
 */
class ReferenceGeneratorIT extends AbstractMySqlIT {

    /** 2026-01-15 23:59:59 in Asia/Kolkata (UTC+05:30). */
    private static final Instant LAST_SECOND_OF_DAY = Instant.parse("2026-01-15T18:29:59Z");
    /** 2026-01-16 00:00:00 in Asia/Kolkata, while UTC is still on the 15th. */
    private static final Instant NEXT_IST_DAY = Instant.parse("2026-01-15T18:30:00Z");
    private static final IdempotencyServiceIT.MutableClock CLOCK =
            new IdempotencyServiceIT.MutableClock(LAST_SECOND_OF_DAY);

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    ReferenceGenerator references;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    static Clock fixedClock() {
        return CLOCK;
    }

    @BeforeEach
    void reset() {
        CLOCK.set(LAST_SECOND_OF_DAY);
        jdbc.update("DELETE FROM ref_sequences", Map.of());
    }

    @Test
    void fiftyParallelCallsIncludingTheFirstOfTheDayGiveFiftyUniqueRefs() throws Exception {
        int calls = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calls);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                Callable<String> call = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return references.next(ReferenceGenerator.SERVICE_REQUEST);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<String> refs = new ArrayList<>();
            for (Future<String> future : futures) {
                refs.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(new HashSet<>(refs)).hasSize(calls);
            assertThat(refs).allMatch(ref -> ref.matches("SR-20260115-[0-9]{6}"));
            Set<String> expected = IntStream.rangeClosed(1, calls)
                    .mapToObj(n -> String.format("SR-20260115-%06d", n)).collect(Collectors.toSet());
            assertThat(new HashSet<>(refs)).isEqualTo(expected);
            assertThat(lastIssued("SR", "2026-01-15")).isEqualTo(calls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void firstCallOfTheDayIsOneAndCounterRestartsOnTheNextAsiaKolkataDay() {
        assertThat(references.next("SR")).isEqualTo("SR-20260115-000001");
        assertThat(references.next("SR")).isEqualTo("SR-20260115-000002");

        CLOCK.set(NEXT_IST_DAY);
        assertThat(references.next("SR")).isEqualTo("SR-20260116-000001");
        assertThat(references.next("SR")).isEqualTo("SR-20260116-000002");
        assertThat(lastIssued("SR", "2026-01-15")).isEqualTo(2);
    }

    @Test
    void eachPrefixHasItsOwnCounter() {
        assertThat(references.next(ReferenceGenerator.SERVICE_REQUEST)).isEqualTo("SR-20260115-000001");
        assertThat(references.next(ReferenceGenerator.PAYMENT)).isEqualTo("PAY-20260115-000001");
        assertThat(references.next(ReferenceGenerator.REACTIVATION)).isEqualTo("REA-20260115-000001");
        assertThat(references.next(ReferenceGenerator.PAYMENT)).isEqualTo("PAY-20260115-000002");
    }

    @Test
    void unknownPrefixIsRejectedWithoutTouchingTheTable() {
        for (String prefix : new String[] {null, "", "XX", "sr", "SR-", "PAYMENT"}) {
            assertThatThrownBy(() -> references.next(prefix)).isInstanceOf(IllegalArgumentException.class);
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ref_sequences", Map.of(), Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void valueTakenInsideARolledBackCallerIsNotReused() {
        tx.executeWithoutResult(status -> {
            assertThat(references.next("PAY")).isEqualTo("PAY-20260115-000001");
            status.setRollbackOnly();
        });
        assertThat(references.next("PAY")).isEqualTo("PAY-20260115-000002");
    }

    @Test
    void sequenceGrowsPastSixDigits() {
        jdbc.update("INSERT INTO ref_sequences (seq_name, seq_date, next_value) VALUES ('REA', '2026-01-15', 999999)",
                Map.of());
        assertThat(references.next("REA")).isEqualTo("REA-20260115-1000000");
    }

    private long lastIssued(String name, String day) {
        Long value = jdbc.queryForObject(
                "SELECT next_value FROM ref_sequences WHERE seq_name = :name AND seq_date = :day",
                Map.of("name", name, "day", day), Long.class);
        return value == null ? 0 : value;
    }
}
