package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.idempotency.IdempotencyPurgeJob;
import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * B5: idempotency service (00-overview §4/§7, 01 backend §6.1/§8; NFR-REL). Time is a mutable fixed clock that replaces
 * the {@code AppClock} bean; the 409 case uses two real threads coordinated by latches.
 */
@Import(IdempotencyServiceIT.IdempotencyProbe.class)
class IdempotencyServiceIT extends AbstractMySqlIT {

    private static final String USERNAME = "T-IN";
    private static final String OPERATION = "PROBE_CREATE";
    private static final String PROBE = "/api/v1/test-idempotency";
    private static final String TYPE = "https://telecom.example/problems/";
    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);

    /** The same SPA-style CSRF pair as {@code ErrorModelIT}. */
    private static final RequestPostProcessor XSRF = request -> {
        String token = "test-xsrf-token";
        request.setCookies(new Cookie("XSRF-TOKEN", token));
        request.addHeader("X-XSRF-TOKEN", token);
        return request;
    };

    @TestBean(name = "clock", methodName = "fixedClock")
    Clock clock;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    IdempotencyPurgeJob purgeJob;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private long userId;

    static Clock fixedClock() {
        return CLOCK;
    }

    record ProbeRequest(String name, Map<String, Object> attributes) {}

    @BeforeEach
    void reset() {
        CLOCK.set(T0);
        jdbc.update("DELETE FROM idempotency_keys", Map.of());
        userId = userId(jdbc, USERNAME);
    }

    @Test
    void completed2xxIsReplayedWithoutRunningTheSupplierAgain() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<?>> create = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(201).body(Map.of("id", 42, "ref", "CASE-1"));
        };

        ResponseEntity<?> first = idempotency.execute(userId, OPERATION, "key-replay", request("a", "x", "y"), create);
        // same content, map keys inserted in the other order: the canonical hash must match
        ResponseEntity<?> second = idempotency.execute(userId, OPERATION, "key-replay", request("a", "y", "x"), create);

        assertThat(calls).hasValue(1);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(objectMapper.valueToTree(first.getBody()));
        assertThat(row("key-replay")).containsEntry("status", "COMPLETED").containsEntry("response_status", 201);
    }

    @Test
    void sameKeyWithDifferentBodyIs422IdempotencyMismatch() throws Exception {
        callProbe("key-mismatch", "{\"name\":\"first\"}").andExpect(status().isCreated());

        problem(callProbe("key-mismatch", "{\"name\":\"second\"}"), 422, "idempotency-mismatch");
        assertThat(row("key-mismatch")).containsEntry("status", "COMPLETED");
    }

    @Test
    void concurrentRequestWithTheSameKeyGets409AtOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<?>> firstCall = executor.submit(() -> idempotency.execute(userId, OPERATION,
                    "key-concurrent", new ProbeRequest("same", null), () -> {
                        started.countDown();
                        await(release);
                        return ResponseEntity.status(201).body(Map.of("id", 1));
                    }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            problem(callProbe("key-concurrent", "{\"name\":\"same\"}"), 409, "conflict");
            assertThat(firstCall).isNotDone();
            assertThat(row("key-concurrent")).containsEntry("status", "IN_PROGRESS");

            release.countDown();
            assertThat(firstCall.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
            assertThat(row("key-concurrent")).containsEntry("status", "COMPLETED");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void supplierExceptionDeletesTheKeySoARetrySucceeds() {
        IllegalStateException failure = new IllegalStateException("downstream failed");
        ProbeRequest request = new ProbeRequest("retry", null);

        assertThatThrownBy(() -> idempotency.execute(userId, OPERATION, "key-retry", request, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(count("key-retry")).isZero();

        ResponseEntity<?> retry = idempotency.execute(userId, OPERATION, "key-retry", request,
                () -> ResponseEntity.status(201).body(Map.of("id", 7)));
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(row("key-retry")).containsEntry("status", "COMPLETED");
    }

    @Test
    void non2xxResponseIsReturnedUnchangedAndNotStored() {
        AtomicInteger calls = new AtomicInteger();
        ProbeRequest request = new ProbeRequest("rejected", null);
        Supplier<ResponseEntity<?>> rejected = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(422).body(Map.of("reason", "not eligible"));
        };

        ResponseEntity<?> first = idempotency.execute(userId, OPERATION, "key-non2xx", request, rejected);
        assertThat(first.getStatusCode().value()).isEqualTo(422);
        assertThat(first.getBody()).isEqualTo(Map.of("reason", "not eligible"));
        assertThat(count("key-non2xx")).isZero();

        idempotency.execute(userId, OPERATION, "key-non2xx", request, rejected);
        assertThat(calls).hasValue(2);
        assertThat(count("key-non2xx")).isZero();
    }

    @Test
    void staleInProgressKeyIsReclaimed() {
        ProbeRequest request = new ProbeRequest("stale", null);
        AtomicInteger reclaimedRuns = new AtomicInteger();

        // the outer call holds the key IN_PROGRESS (claim committed) while its supplier runs
        ResponseEntity<?> outer = idempotency.execute(userId, OPERATION, "key-stale", request, () -> {
            CLOCK.set(T0.plus(Duration.ofMinutes(2)));
            assertThatThrownBy(() -> idempotency.execute(userId, OPERATION, "key-stale", request,
                    () -> ResponseEntity.ok(Map.of()))).isInstanceOf(ConflictException.class);

            CLOCK.set(T0.plus(Duration.ofMinutes(2)).plusSeconds(1));
            ResponseEntity<?> reclaimed = idempotency.execute(userId, OPERATION, "key-stale", request, () -> {
                reclaimedRuns.incrementAndGet();
                return ResponseEntity.status(201).body(Map.of("id", "reclaimed"));
            });
            assertThat(reclaimed.getStatusCode().value()).isEqualTo(201);
            return ResponseEntity.status(201).body(Map.of("id", "stale-original"));
        });

        assertThat(outer.getStatusCode().value()).isEqualTo(201);
        assertThat(reclaimedRuns).hasValue(1);
        assertThat(count("key-stale")).isEqualTo(1);
        // the stale call's completion no longer owns the row: the reclaimed response is what is stored
        assertThat(row("key-stale")).containsEntry("status", "COMPLETED");
        assertThat((String) row("key-stale").get("response_body")).contains("reclaimed");
    }

    @Test
    void keysArePurgedAfter24Hours() {
        ProbeRequest request = new ProbeRequest("purge", null);
        idempotency.execute(userId, OPERATION, "key-old", request, () -> ResponseEntity.ok(Map.of("id", 1)));

        CLOCK.set(T0.plus(Duration.ofHours(24)));
        idempotency.execute(userId, OPERATION, "key-new", request, () -> ResponseEntity.ok(Map.of("id", 2)));
        assertThat(purgeJob.purge()).isZero();
        assertThat(count("key-old")).isEqualTo(1);

        CLOCK.set(T0.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(purgeJob.purge()).isEqualTo(1);
        assertThat(count("key-old")).isZero();
        assertThat(count("key-new")).isEqualTo(1);
    }

    private static ProbeRequest request(String name, String firstKey, String secondKey) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(firstKey, firstKey.toUpperCase());
        attributes.put(secondKey, secondKey.toUpperCase());
        return new ProbeRequest(name, attributes);
    }

    private ResultActions callProbe(String key, String json) throws Exception {
        return mvc.perform(post(PROBE).with(user(USERNAME)).with(XSRF).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static void problem(ResultActions actions, int status, String type) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE + type))
                .andExpect(jsonPath("$.instance").value(PROBE))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("""
                SELECT status, response_status, response_body FROM idempotency_keys
                WHERE user_id = :userId AND operation = :operation AND idem_key = :key
                """, params(key));
    }

    private int count(String key) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_keys
                WHERE user_id = :userId AND operation = :operation AND idem_key = :key
                """, params(key), Integer.class);
        return n == null ? 0 : n;
    }

    private MapSqlParameterSource params(String key) {
        return new MapSqlParameterSource().addValue("userId", userId).addValue("operation", OPERATION)
                .addValue("key", key);
    }

    private static long userId(NamedParameterJdbcTemplate jdbc, String username) {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                Map.of("username", username), Long.class);
        return id == null ? 0 : id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A UTC clock whose instant the test sets. */
    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed UTC test clock");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Test-only idempotent endpoint: exercises the 409 / 422 mapping through the full filter chain. */
    @TestComponent
    @RestController
    static class IdempotencyProbe {

        private final IdempotencyService idempotency;
        private final NamedParameterJdbcTemplate jdbc;

        IdempotencyProbe(IdempotencyService idempotency, NamedParameterJdbcTemplate jdbc) {
            this.idempotency = idempotency;
            this.jdbc = jdbc;
        }

        @PostMapping(PROBE)
        ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String key, @RequestBody ProbeRequest body,
                                 Principal principal) {
            return idempotency.execute(userId(jdbc, principal.getName()), OPERATION, key, body,
                    () -> ResponseEntity.status(201).body(Map.of("name", body.name())));
        }
    }
}
