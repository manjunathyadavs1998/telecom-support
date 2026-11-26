package com.telecom.support.payment;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.error.ApiExceptions.DependencyUnavailableException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.reference.ReferenceGenerator;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.payment.InvoiceRepository.InvoiceBalance;
import com.telecom.support.payment.PaymentGateway.CheckoutSession;
import com.telecom.support.payment.PaymentGateway.GatewayException;
import com.telecom.support.payment.PaymentGateway.GatewayPaymentStatus;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.GatewayTimeoutException;
import com.telecom.support.payment.PaymentQueryService.PaymentStatus;
import com.telecom.support.payment.PaymentRepository.InvoicePayment;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Source;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E15: start paying the full outstanding amount of one own invoice (design 02-customer backend R12, R16, §3.1, §7, §8,
 * erratum E1; CUS-08.1/08.2/08.4, NFR-REL, NFR-AVAIL). The account comes from the principal only.
 *
 * <ol>
 *   <li>Checks (no lock): account mutable (R16; a SUSPENDED account may pay, OQ 12), own invoice (else 404), OPEN with
 *       outstanding &gt; 0 equal to {@code expectedAmount} (full payment only, OQ 8; else 422), no PENDING or REVIEW
 *       payment on it (409).</li>
 *   <li>Late-capture re-check, no transaction open: every FAILED/EXPIRED payment of the invoice completed within
 *       {@code late-capture-window} (24 h) is asked {@code getStatus}. SUCCEEDED → applied through
 *       {@link PaymentTransitionService} and 409; PENDING (a live checkout) → 409; unreachable → 503.</li>
 *   <li>tx1: locks the invoice ({@code SELECT … FOR UPDATE}, erratum E1), repeats the checks, generates the PAY
 *       reference and inserts the PENDING payment, commits. The reference is the gateway merchant reference.</li>
 *   <li>{@code createCheckout} with the server-built {@code returnUrl}, no transaction open.</li>
 *   <li>tx2: ok → session id and redirect URL stored, {@code PAYMENT_INITIATED} SUCCESS → 201. Refused → FAILED
 *       {@code gateway_unavailable} (via {@link PaymentTransitionService}) → 503. Timeout → stays PENDING
 *       {@code gateway_timeout} → 503, reconciled by reference. Both failures audit {@code PAYMENT_INITIATED}
 *       FAILURE.</li>
 * </ol>
 * The controller wraps the call in the idempotency service, which releases the key on a 503 so a retry works.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String CURRENCY = "INR";
    static final String AUDIT_INITIATED = "PAYMENT_INITIATED";
    static final String REASON_GATEWAY_UNAVAILABLE = "gateway_unavailable";
    static final String REASON_GATEWAY_TIMEOUT = "gateway_timeout";
    /** SPA page the gateway sends the browser back to; it polls E16 for the result (CUS-08.3). */
    static final String RETURN_PATH = "/customer/payments/";

    private static final String PENDING = PaymentTransitionService.PENDING;
    private static final String REVIEW = PaymentTransitionService.REVIEW;
    private static final List<String> RECHECKED = List.of(PaymentTransitionService.FAILED,
            PaymentTransitionService.EXPIRED);

    /** The {@code PaymentInitiated} schema. */
    public record PaymentInitiated(String reference, PaymentStatus status, String redirectUrl) {}

    private record Pending(long id, String reference, String invoiceRef, BigDecimal amount) {}

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentTransitionService transitions;
    private final PaymentGateway gateway;
    private final AccountLookup accounts;
    private final CustomerScope scope;
    private final ActorProvider actors;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Duration lateCaptureWindow;
    private final String returnUrlBase;

    PaymentService(InvoiceRepository invoices, PaymentRepository payments, PaymentTransitionService transitions,
                   PaymentGateway gateway, AccountLookup accounts, CustomerScope scope, ActorProvider actors,
                   ReferenceGenerator references, AuditService audit, Clock clock,
                   PlatformTransactionManager transactionManager, PaymentGatewayProperties properties) {
        this.invoices = invoices;
        this.payments = payments;
        this.transitions = transitions;
        this.gateway = gateway;
        this.accounts = accounts;
        this.scope = scope;
        this.actors = actors;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.lateCaptureWindow = properties.gateway().lateCaptureWindow();
        this.returnUrlBase = properties.returnUrlBase() == null ? null
                : properties.returnUrlBase().replaceAll("/+$", "");
    }

    /** E15 (R12). Not transactional itself: tx1 commits before the gateway call and tx2 runs after it (§3.1). */
    public PaymentInitiated initiate(long invoiceId, BigDecimal expectedAmount) {
        if (returnUrlBase == null) {
            throw new IllegalStateException("app.payment.return-url-base is not configured");
        }
        Actor actor = actors.currentActor().orElseThrow();
        CustomerAccount account = myAccount();
        account.requireMutable();

        List<InvoicePayment> existing = tx.execute(status -> {
            InvoiceBalance invoice = invoices.lockOwn(account.accountId(), invoiceId)
                    .orElseThrow(() -> notFound(account, invoiceId));
            requirePayable(invoice, expectedAmount);
            return requireNoOpenPayment(invoice);
        });
        recheckRecentFailures(existing);

        Pending pending = tx.execute(status -> insertPending(account, actor, invoiceId, expectedAmount));
        return openCheckout(pending, actor);
    }

    /** tx1 (§3.1, erratum E1): under the invoice lock, re-checks R12 and inserts the PENDING payment. */
    private Pending insertPending(CustomerAccount account, Actor actor, long invoiceId, BigDecimal expectedAmount) {
        InvoiceBalance invoice = invoices.lockOwn(account.accountId(), invoiceId)
                .orElseThrow(() -> notFound(account, invoiceId));
        requirePayable(invoice, expectedAmount);
        requireNoOpenPayment(invoice);
        String reference = references.next(ReferenceGenerator.PAYMENT);
        BigDecimal amount = invoice.amountDue().subtract(invoice.amountPaid());
        try {
            long id = payments.insertPending(reference, account.accountId(), invoice.id(), amount, CURRENCY,
                    actor.id(), clock.instant());
            return new Pending(id, reference, invoice.invoiceRef(), amount);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Invoice " + invoice.invoiceRef() + " already has a PENDING payment");
        }
    }

    /** Gateway call outside any transaction, then tx2 (§3.1 rows "tx2 after …"). */
    private PaymentInitiated openCheckout(Pending pending, Actor actor) {
        String returnUrl = returnUrlBase + RETURN_PATH + pending.reference();
        CheckoutSession session;
        try {
            session = gateway.createCheckout(pending.reference(), pending.amount(), CURRENCY, returnUrl);
        } catch (GatewayTimeoutException e) {
            log.warn("Payment {}: createCheckout timed out; stays PENDING for reconciliation", pending.reference());
            tx.executeWithoutResult(status ->
                    payments.markPendingReason(pending.id(), REASON_GATEWAY_TIMEOUT, clock.instant()));
            initiateFailed(pending, actor, REASON_GATEWAY_TIMEOUT);
            throw new DependencyUnavailableException("Payment gateway timed out for " + pending.reference());
        } catch (GatewayException e) {
            log.warn("Payment {}: createCheckout refused: {}", pending.reference(), e.getMessage());
            transitions.failed(pending.reference(), REASON_GATEWAY_UNAVAILABLE, null, Source.INITIATE);
            initiateFailed(pending, actor, REASON_GATEWAY_UNAVAILABLE);
            throw new DependencyUnavailableException("Payment gateway refused " + pending.reference());
        }

        tx.executeWithoutResult(status -> {
            if (payments.storeCheckout(pending.id(), session.sessionId(), session.redirectUrl(),
                    clock.instant()) != 1) {
                throw new ConflictException("Payment " + pending.reference() + " left PENDING before tx2");
            }
            audit.record(AUDIT_INITIATED, actor.id(), actor.username(), PaymentTransitionService.ENTITY_PAYMENT,
                    pending.reference(), details(pending, null));
        });
        return new PaymentInitiated(pending.reference(), PaymentStatus.PENDING, session.redirectUrl());
    }

    /**
     * R12 late-capture re-check, outside any transaction: a recent FAILED/EXPIRED payment may still be captured. SUCCEEDED
     * is applied (§3.1, R14 credit) and PENDING means a live checkout; both → 409. Unreachable → 503.
     */
    private void recheckRecentFailures(List<InvoicePayment> invoicePayments) {
        for (InvoicePayment payment : recentFailures(invoicePayments)) {
            GatewayPaymentStatus status;
            try {
                status = gateway.getStatus(payment.reference());
            } catch (GatewayException e) {
                throw new DependencyUnavailableException("Gateway re-check of " + payment.reference() + " failed: "
                        + e.getMessage());
            }
            if (status.status() == GatewayStatus.SUCCEEDED) {
                transitions.succeeded(payment.reference(), new Capture(status.sessionId(), status.amount(),
                        status.currency(), status.method(), status.gatewayTxnId()), Source.INITIATE);
                throw new ConflictException("Payment " + payment.reference() + " was captured late");
            }
            if (status.status() == GatewayStatus.PENDING) {
                throw new ConflictException("Payment " + payment.reference() + " still has a live checkout");
            }
        }
    }

    private List<InvoicePayment> recentFailures(List<InvoicePayment> invoicePayments) {
        Instant since = clock.instant().minus(lateCaptureWindow);
        return invoicePayments.stream()
                .filter(p -> RECHECKED.contains(p.status()))
                .filter(p -> p.completedAt() != null && p.completedAt().isAfter(since))
                .toList();
    }

    /** OPEN, outstanding &gt; 0 and equal to {@code expectedAmount} (full payment only, OQ 8); else 422. */
    private static void requirePayable(InvoiceBalance invoice, BigDecimal expectedAmount) {
        BigDecimal outstanding = invoice.amountDue().subtract(invoice.amountPaid());
        if (!"OPEN".equals(invoice.status()) || outstanding.signum() <= 0) {
            throw new BusinessRuleException("Invoice " + invoice.invoiceRef() + " is not payable: " + invoice.status()
                    + ", outstanding " + outstanding, "This invoice has nothing left to pay.");
        }
        if (outstanding.compareTo(expectedAmount) != 0) {
            throw new BusinessRuleException("Invoice " + invoice.invoiceRef() + " outstanding " + outstanding
                    + " differs from expected " + expectedAmount,
                    "The amount to pay has changed. Refresh the invoice and try again.");
        }
    }

    /** Erratum E1: any PENDING or REVIEW payment on the invoice blocks a new one (409). Returns all its payments. */
    private List<InvoicePayment> requireNoOpenPayment(InvoiceBalance invoice) {
        List<InvoicePayment> all = payments.findByInvoice(invoice.id());
        if (all.stream().anyMatch(p -> PENDING.equals(p.status()) || REVIEW.equals(p.status()))) {
            throw new ConflictException("Invoice " + invoice.invoiceRef() + " has a PENDING or REVIEW payment");
        }
        return all;
    }

    private void initiateFailed(Pending pending, Actor actor, String reason) {
        audit.recordIndependently(AUDIT_INITIATED, Outcome.FAILURE, actor.id(), actor.username(),
                PaymentTransitionService.ENTITY_PAYMENT, pending.reference(), details(pending, reason));
    }

    private static Map<String, Object> details(Pending pending, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceRef", pending.invoiceRef());
        details.put("amount", PaymentQueryService.money(pending.amount()));
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }

    private CustomerAccount myAccount() {
        long customerId = scope.requireCustomerId();
        return accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"));
    }

    private static NotFoundException notFound(CustomerAccount account, long invoiceId) {
        return new NotFoundException("Invoice " + invoiceId + " not found in account " + account.accountId());
    }
}
