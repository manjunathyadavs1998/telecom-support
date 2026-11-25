package com.telecom.support.payment;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.TooManyRequestsException;
import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import com.telecom.support.common.web.ClientIp;
import com.telecom.support.common.web.RequestSizeLimitAdvice.PayloadTooLargeException;
import com.telecom.support.payment.PaymentGateway.GatewayEvent;
import com.telecom.support.payment.PaymentGateway.WebhookPayloadException;
import com.telecom.support.payment.PaymentGateway.WebhookSignatureException;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Result;
import com.telecom.support.payment.PaymentTransitionService.Source;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * E18 {@code POST /api/v1/payment-gateway/webhooks}: signed payment status events from the gateway, server to server
 * (design 02-customer backend §3.1, §4, §7, §8; CUS-08.3/08.4, CUS-07.5, BR-08, NFR-SEC, NFR-INTEG).
 *
 * <p>Public, no session, CSRF-exempt ({@code SecurityConfig}); authenticated only by the HMAC. The §8 order is kept:
 * <ol>
 *   <li>per-client-IP limit ({@link WebhookRateLimiter}) → 429 with {@code Retry-After}, before the body is read;</li>
 *   <li>the raw body bytes are read (over {@code app.web.max-request-size} → 413), never parsed yet;</li>
 *   <li>{@link PaymentGateway#verifyWebhook}: signature over the raw bytes + timestamp tolerance → 401, so a forged
 *       call gets 401 even when its body is malformed; then parse/validate → 400 ({@code payment.succeeded} needs
 *       {@code gatewayTransactionId} and {@code method});</li>
 *   <li>dedupe: the event id is inserted into {@code payment_gateway_events} (unique) — a duplicate is a 204 no-op;</li>
 *   <li>the §3.1 transition through {@link PaymentTransitionService}, in the same transaction as the event row.</li>
 * </ol>
 * Accepted events answer 204 whether applied, ignored (any pair §3.1 does not allow) or for an unknown payment
 * ({@code PAYMENT_WEBHOOK_REJECTED} {@code unknown_payment}, event {@code REJECTED}). A signature rejection writes
 * {@code PAYMENT_WEBHOOK_REJECTED} ({@code bad_signature}/{@code stale}) in its own transaction, sampled once per
 * client IP per minute with a WARN log. Nothing from the body is logged.
 */
@RestController
class PaymentWebhookController {

    static final String PATH = "/api/v1/payment-gateway/webhooks";
    static final String AUDIT_REJECTED = "PAYMENT_WEBHOOK_REJECTED";
    static final String REASON_UNKNOWN_PAYMENT = "unknown_payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentGateway gateway;
    private final WebhookRateLimiter limiter;
    private final GatewayEventRepository events;
    private final PaymentTransitionService transitions;
    private final AuditService audit;
    private final ProblemDetailFactory problems;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final long maxBytes;

    PaymentWebhookController(PaymentGateway gateway, WebhookRateLimiter limiter, GatewayEventRepository events,
                             PaymentTransitionService transitions, AuditService audit, ProblemDetailFactory problems,
                             PlatformTransactionManager transactionManager, Clock clock,
                             @Value("${app.web.max-request-size:1MB}") DataSize maxRequestSize) {
        this.gateway = gateway;
        this.limiter = limiter;
        this.events = events;
        this.transitions = transitions;
        this.audit = audit;
        this.problems = problems;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxBytes = maxRequestSize.toBytes();
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    ResponseEntity<?> receiveGatewayWebhook(HttpServletRequest request,
                                          @RequestHeader HttpHeaders headers) throws IOException {
        String clientIp = ClientIp.resolve(request);
        OptionalLong retryAfter = limiter.acquire(clientIp);
        if (retryAfter.isPresent()) {
            throw new TooManyRequestsException("Webhook limit reached for a client IP", retryAfter.getAsLong());
        }
        byte[] raw = readRaw(request);

        GatewayEvent event;
        try {
            event = gateway.verifyWebhook(headers, raw);
        } catch (WebhookSignatureException rejected) {
            if (limiter.sampleRejection(clientIp)) {
                log.warn("Webhook rejected ({}) from {}", rejected.reason().auditValue(), clientIp);
                audit.recordIndependently(AUDIT_REJECTED, Outcome.FAILURE, null, null,
                        PaymentTransitionService.ENTITY_PAYMENT, null,
                        Map.of("reason", rejected.reason().auditValue()));
            }
            ProblemDetail problem = problems.create(Problem.UNAUTHENTICATED, request.getRequestURI());
            return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        } catch (WebhookPayloadException invalid) {
            throw new BadRequestException(invalid.getMessage(), invalid.getMessage());
        }

        Boolean unknownPayment = tx.execute(status -> apply(event));
        if (Boolean.TRUE.equals(unknownPayment)) {
            audit.recordIndependently(AUDIT_REJECTED, Outcome.FAILURE, null, null,
                    PaymentTransitionService.ENTITY_PAYMENT, event.paymentReference(),
                    details("reason", REASON_UNKNOWN_PAYMENT, "eventType", event.type().wireName()));
        }
        return ResponseEntity.noContent().build();
    }

    /** Dedupe + §3.1 transition in one transaction. Returns true when the payment is unknown. */
    private boolean apply(GatewayEvent event) {
        String eventType = event.type().wireName();
        if (!events.insertIfNew(event.eventId(), eventType, GatewayEventRepository.Outcome.IGNORED, clock.instant())) {
            return false; // duplicate delivery: already recorded, nothing changes
        }
        Result result = switch (event.type()) {
            case SUCCEEDED -> transitions.succeeded(event.paymentReference(), new Capture(event.sessionId(),
                    event.amount(), event.currency(), event.method(), event.gatewayTransactionId()), Source.WEBHOOK);
            case FAILED -> transitions.failed(event.paymentReference(), event.failureReason(), event.method(),
                    Source.WEBHOOK);
            case EXPIRED -> transitions.expired(event.paymentReference(), Source.WEBHOOK);
        };
        GatewayEventRepository.Outcome outcome = switch (result.kind()) {
            case APPLIED -> GatewayEventRepository.Outcome.APPLIED;
            case IGNORED -> GatewayEventRepository.Outcome.IGNORED;
            case UNKNOWN_PAYMENT -> GatewayEventRepository.Outcome.REJECTED;
        };
        events.complete(event.eventId(), event.paymentReference(), outcome);
        return result.kind() == Result.Kind.UNKNOWN_PAYMENT;
    }

    /** The raw body, at most {@code app.web.max-request-size} bytes (413 beyond). */
    private byte[] readRaw(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new PayloadTooLargeException();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] raw = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (raw.length > maxBytes) {
                throw new PayloadTooLargeException();
            }
            return raw;
        }
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                map.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
