package com.telecom.support.payment;

import com.telecom.support.payment.PaymentQueryService.Invoice;
import com.telecom.support.payment.PaymentQueryService.InvoiceStatus;
import com.telecom.support.payment.PaymentQueryService.Payment;
import com.telecom.support.payment.PaymentQueryService.PaymentPage;
import com.telecom.support.payment.PaymentQueryService.PaymentSort;
import com.telecom.support.payment.PaymentQueryService.Receipt;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/invoices} and {@code /api/v1/customers/me/payments} reads — E13, E14, E16, E17 (design
 * 02-customer backend §4, §5, R1, R11, R15; openapi.yaml; CUS-07.1–07.4, CUS-08.1, CUS-08.3–08.5). Each needs
 * {@code payment.read} and a customer principal with a linked customer, so staff (including INBOUND_AGENT, which holds
 * {@code payment.read}) and unlinked customers get 403. The account comes from the principal only.
 */
@RestController
@RequestMapping("/api/v1/customers/me")
public class CustomerPaymentController {

    private final PaymentQueryService queries;

    CustomerPaymentController(PaymentQueryService queries) {
        this.queries = queries;
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
