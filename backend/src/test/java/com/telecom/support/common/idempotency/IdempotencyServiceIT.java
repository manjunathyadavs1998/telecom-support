package com.telecom.support.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.AbstractWebIntegrationTest;
import com.telecom.support.common.error.Errors.BadRequestException;
import com.telecom.support.common.error.Errors.BusinessRuleException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

class IdempotencyServiceIT extends AbstractWebIntegrationTest {

    record Created(String reference) {}

    @Autowired
    IdempotencyService idempotency;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameKeyAndPayloadReplaysOriginalResponseWithoutRunningActionTwice() {
        runAs("customer1");
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();

        Created first = idempotency.execute("test.create", key, Map.of("a", 1), Created.class,
                () -> new Created("REF-" + executions.incrementAndGet()));
        Created second = idempotency.execute("test.create", key, Map.of("a", 1), Created.class,
                () -> new Created("REF-" + executions.incrementAndGet()));

        assertThat(second).isEqualTo(first);
        assertThat(executions).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        runAs("customer1");
        String key = UUID.randomUUID().toString();
        idempotency.execute("test.create", key, Map.of("a", 1), Created.class, () -> new Created("X"));

        assertThatThrownBy(() -> idempotency.execute("test.create", key, Map.of("a", 2), Created.class, () -> new Created("Y")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void failedActionReleasesKeySoClientCanRetry() {
        runAs("customer2");
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> idempotency.execute("test.create", key, Map.of(), Created.class, () -> {
            throw new IllegalStateException("provider down");
        })).isInstanceOf(IllegalStateException.class);

        Created retried = idempotency.execute("test.create", key, Map.of(), Created.class, () -> new Created("OK"));
        assertThat(retried.reference()).isEqualTo("OK");
    }

    @Test
    void malformedKeyIsRejected() {
        runAs("customer1");
        assertThatThrownBy(() -> idempotency.execute("test.create", "bad key!", Map.of(), Created.class, () -> new Created("X")))
                .isInstanceOf(BadRequestException.class);
    }
}
