package com.telecom.support.payment;

import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.payment.PaymentQueryService.Invoice;
import com.telecom.support.payment.PaymentQueryService.InvoiceStatus;
import com.telecom.support.payment.PaymentQueryService.PaymentPage;
import com.telecom.support.payment.PaymentQueryService.PaymentSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/customers/{customerId}/invoices} and {@code /payments} — I7, I8 of the inbound verified view
 * (design 03-inbound backend R5, R7, §4, §5; openapi.yaml; INB-01.3, BR-05/06). Each needs {@code customer.lookup} and
 * {@code payment.read}; the account comes from the {@link VerifiedCustomer} of the verified-scope interceptor, never
 * from the path id. Same data as E13/E14; no receipts.
 */
@RestController
@RequestMapping("/api/v1/agent/customers/{customerId}")
public class AgentPaymentController {

    private final PaymentQueryService queries;

    AgentPaymentController(PaymentQueryService queries) {
        this.queries = queries;
    }

    /** I7: latest 12 invoices. An unknown {@code status} value → 400. */
    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)")
    public List<Invoice> invoices(VerifiedCustomer verified,
                                  @RequestParam(required = false) InvoiceStatus status) {
        return queries.invoicesOf(verified, status);
    }

    /** I8: payments initiated in the window (default 3 months; {@code to} only → 3 months before it), paged. */
    @GetMapping("/payments")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).PAYMENT_READ)")
    public PaymentPage payments(
            VerifiedCustomer verified,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return queries.paymentsOf(verified, from, to, page, size, PaymentSort.fromParam(sort));
    }
}
