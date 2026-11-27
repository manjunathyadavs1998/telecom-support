package com.telecom.support.customer;

import com.telecom.support.common.idempotency.IdempotencyService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only verified routes (design 03-inbound backend R5): a read and an idempotent create under
 * {@code /api/v1/agent/customers/{customerId}/**}, guarded like the real ones, so {@code VerifiedCustomerAccessIT} can
 * prove the interceptor (including a replay after expiry) independently of any feature endpoint. {@link TestComponent}:
 * pulled in with {@code @Import} by the inbound ITs that share one context, never shipped.
 */
@TestComponent
@RestController
public class VerifiedProbeController {

    static final String PATH = "/api/v1/agent/customers/{customerId}/test-probe";
    static final String OPERATION = "TEST_VERIFIED_PROBE";
    static final AtomicInteger CREATES = new AtomicInteger();

    public record ProbeRequest(String name) {}

    private final IdempotencyService idempotency;

    VerifiedProbeController(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @GetMapping(PATH)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_READ)")
    public Map<String, Object> read(@PathVariable long customerId, VerifiedCustomer verified) {
        return Map.of("customerId", verified.customerId(), "accountId", verified.accountId(),
                "checkId", verified.checkId(), "agentUserId", verified.agentUserId());
    }

    @PostMapping(PATH)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CASE_CREATE_ON_BEHALF)")
    public ResponseEntity<?> create(@PathVariable long customerId, VerifiedCustomer verified,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    @RequestBody ProbeRequest body) {
        return idempotency.execute(verified.agentUserId(), OPERATION, key, Map.of("customerId", customerId,
                        "body", body),
                () -> ResponseEntity.status(201).body(Map.of("n", CREATES.incrementAndGet(), "name", body.name())));
    }
}
