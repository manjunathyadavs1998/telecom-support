package com.telecom.support.payment;

import com.telecom.support.payment.PaymentQueryService.Invoice;
import com.telecom.support.payment.PaymentQueryService.InvoiceStatus;
import com.telecom.support.payment.PaymentQueryService.Payment;
import com.telecom.support.payment.PaymentQueryService.PaymentPage;
import com.telecom.support.payment.PaymentQueryService.PaymentSort;
import com.telecom.support.payment.PaymentQueryService.Receipt;
import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.common.security.ActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/invoices} and {@code /api/v1/customers/me/payments} — reads E13, E14, E16, E17 and
 * initiate E15 (design 02-customer backend §4, §5, R1, R11, R12, R15; openapi.yaml; CUS-07.1–07.4, CUS-08.1–08.5).
 * Reads need {@code payment.read}, E15 {@code payment.initiate}, each with a customer principal with a linked customer,
 * so staff (including INBOUND_AGENT, which holds {@code payment.read}) and unlinked customers get 403. The account
 * comes from the principal only; unknown JSON fields ({@code accountId}, {@code amount}, {@code returnUrl}) are
 * ignored.
 */
@RestController
@RequestMapping("/api/v1/customers/me")
public class CustomerPaymentController {

    /** Idempotency operation of E15 (design §5). */
    static final String PAYMENT_INITIATE_OPERATION = "PAYMENT_INITIATE";

    /** The {@code PaymentInitiateRequest} schema ({@code expectedAmount} is Money). */
    public record PaymentInitiateRequest(
            @NotNull Long invoiceId,
            @NotNull @Pattern(regexp = "^\\d{1,10}\\.\\d{2}$", message = "must be an amount like 499.00")
            String expectedAmount) {}

    private final PaymentQueryService queries;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final ActorProvider actors;

    CustomerPaymentController(PaymentQueryService queries, PaymentService payments, IdempotencyService idempotency,
                              ActorProvider actors) {
        this.queries = queries;
        this.payments = payments;
        this.idempotency = idempotency;
        this.actors = actors;
    }

    /** E13. An unknown {@code status} value → 400. */
    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)"
            + " and @customerScope.isCustomer()")
    public List<Invoice> listMyInvoices(@RequestParam(required = false) InvoiceStatus status) {
        return queries.myInvoices(status);
    }

    /** E14. */
    @GetMapping("/payments")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)"
            + " and @customerScope.isCustomer()")
    public PaymentPage listMyPayments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return queries.myPayments(from, to, page, size, PaymentSort.fromParam(sort));
    }

    /**
     * E15 (R12): 201 {@code PaymentInitiated}; the browser goes to {@code redirectUrl}. The canonical idempotent request
     * is the body alone (no path variables, D6). A 503 releases the key, so the same key can be retried.
     */
    @PostMapping(path = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_INITIATE)"
            + " and @customerScope.isCustomer()")
    public ResponseEntity<?> initiateMyPayment(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentInitiateRequest body) {
        long userId = actors.currentActor().orElseThrow().id();
        return idempotency.execute(userId, PAYMENT_INITIATE_OPERATION, idempotencyKey, body,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(payments.initiate(body.invoiceId(), new BigDecimal(body.expectedAmount()))));
    }

    /** E16. A reference that is not one of the account's payments (whatever its shape) → 404. */
    @GetMapping("/payments/{reference}")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)"
            + " and @customerScope.isCustomer()")
    public Payment getMyPayment(@PathVariable String reference) {
        return queries.myPayment(reference);
    }

    /** E17. Not SUCCEEDED → 422. */
    @GetMapping("/payments/{reference}/receipt")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)"
            + " and @customerScope.isCustomer()")
    public Receipt getMyPaymentReceipt(@PathVariable String reference) {
        return queries.myReceipt(reference);
    }
}
