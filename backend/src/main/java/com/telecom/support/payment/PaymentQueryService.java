package com.telecom.support.payment;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.CustomerService;
import com.telecom.support.payment.InvoiceRepository.InvoiceRow;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentRepository.Order;
import com.telecom.support.payment.PaymentRepository.PaymentRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer's invoices (E13), payment history (E14), one payment (E16) and its receipt (E17) (design
 * 02-customer backend R1, R11, R15; CUS-07.1–07.4, CUS-08.1, CUS-08.3–08.5). The account always comes from the principal
 * ({@link CustomerScope}, {@link AccountLookup}); another account's payment reference is simply not found (404). Reads are
 * allowed in every account state (R16). Read-only: payment status changes only through §3.1.
 */
@Service
public class PaymentQueryService {

    /** E13 returns at most this many invoices (openapi {@code listMyInvoices}). */
    static final int LATEST_INVOICES = 12;
    /** Default list window (00-overview §3, BR-05/06) and the largest allowed one. */
    static final int DEFAULT_WINDOW_MONTHS = 3;
    static final long MAX_WINDOW_DAYS = 366;

    public enum InvoiceStatus { OPEN, PAID }

    public enum PaymentStatus { PENDING, SUCCEEDED, FAILED, EXPIRED, REVIEW }

    /** Whitelisted sort keys of E14 (R11). */
    public enum PaymentSort {
        INITIATED_AT_DESC("initiatedAt,desc", Order.INITIATED_AT_DESC),
        INITIATED_AT_ASC("initiatedAt,asc", Order.INITIATED_AT_ASC);

        private final String param;
        private final Order order;

        PaymentSort(String param, Order order) {
            this.param = param;
            this.order = order;
        }

        /** The sort for a {@code sort} query value; null means the default; anything else → 400. */
        public static PaymentSort fromParam(String value) {
            if (value == null) {
                return INITIATED_AT_DESC;
            }
            for (PaymentSort sort : values()) {
                if (sort.param.equals(value)) {
                    return sort;
                }
            }
            throw new BadRequestException("Unsupported payment sort " + value,
                    "Sort must be initiatedAt,desc or initiatedAt,asc.");
        }
    }

    /** The {@code Invoice} schema; money fields are {@code Money} strings ("399.00"). */
    public record Invoice(long id, String invoiceRef, LocalDate periodStart, LocalDate periodEnd, LocalDate issueDate,
                          LocalDate dueDate, String amountDue, String amountPaid, String outstanding,
                          InvoiceStatus status) {}

    /** The {@code Payment} schema. */
    public record Payment(String reference, String invoiceRef, String amount, String currency, Method method,
                          PaymentStatus status, String failureReason, Instant initiatedAt, Instant completedAt,
                          boolean receiptAvailable) {}

    /** The {@code PaymentPage} schema (00-overview §3). */
    public record PaymentPage(List<Payment> items, int page, int size, long totalItems, int totalPages) {}

    /** The {@code Receipt} schema (JSON, OQ 9: the frontend renders the printable page). */
    public record Receipt(String paymentReference, String invoiceRef, String accountRef, String customerName,
                          String amount, String currency, Method method, String gatewayTransactionId, Instant paidAt) {}

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final AccountLookup accounts;
    private final CustomerService customers;
    private final CustomerScope scope;
    private final Clock clock;
    private final ZoneId zone;

    PaymentQueryService(InvoiceRepository invoices, PaymentRepository payments, AccountLookup accounts,
                        CustomerService customers, CustomerScope scope, Clock clock,
                        @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.invoices = invoices;
        this.payments = payments;
        this.accounts = accounts;
        this.customers = customers;
        this.scope = scope;
        this.clock = clock;
        this.zone = zone;
    }

    /** E13: the account's latest 12 invoices, newest issue date first, optionally only {@code status}. */
    @Transactional(readOnly = true)
    public List<Invoice> myInvoices(InvoiceStatus status) {
        return invoices.findLatest(myAccount().accountId(), status == null ? null : status.name(), LATEST_INVOICES)
                .stream()
                .map(PaymentQueryService::toInvoice)
                .toList();
    }

    /**
     * E14 (R11): the account's payments initiated between {@code from} and {@code to} (inclusive Asia/Kolkata dates;
     * default today minus 3 months .. today; only {@code to} given → 3 months before it), newest first unless sorted
     * otherwise. {@code from > to} or a range over 366
     * days → 400.
     */
    @Transactional(readOnly = true)
    public PaymentPage myPayments(LocalDate from, LocalDate to, int page, int size, PaymentSort sort) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusMonths(DEFAULT_WINDOW_MONTHS);
        if (start.isAfter(end)) {
            throw new BadRequestException("Payment list from " + start + " after to " + end,
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_WINDOW_DAYS) {
            throw new BadRequestException(
                    "Payment list range " + start + ".." + end + " over " + MAX_WINDOW_DAYS + " days",
                    "The date range can be at most 366 days.");
        }
        long accountId = myAccount().accountId();
        Instant fromInclusive = start.atStartOfDay(zone).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(zone).toInstant();

        long total = payments.count(accountId, fromInclusive, toExclusive);
        List<Payment> items = total == 0 ? List.of()
                : payments.findPage(accountId, fromInclusive, toExclusive, sort.order, size, (long) page * size)
                        .stream()
                        .map(PaymentQueryService::toPayment)
                        .toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new PaymentPage(items, page, size, total, totalPages);
    }

    /** E16: one own payment (polled after the gateway return, CUS-08.3); absent or another account's → 404. */
    @Transactional(readOnly = true)
    public Payment myPayment(String reference) {
        return toPayment(load(myAccount(), reference));
    }

    /** E17 (R15): receipt data of an own SUCCEEDED payment; absent or another account's → 404, not SUCCEEDED → 422. */
    @Transactional(readOnly = true)
    public Receipt myReceipt(String reference) {
        CustomerAccount account = myAccount();
        PaymentRow row = load(account, reference);
        if (!PaymentStatus.SUCCEEDED.name().equals(row.status())) {
            throw new BusinessRuleException("Receipt for payment " + reference + " in status " + row.status(),
                    "A receipt is available only for a successful payment.");
        }
        return new Receipt(row.reference(), row.invoiceRef(), account.accountRef(), customers.myProfile().fullName(),
                money(row.amount()), row.currency(), method(row.method()), row.gatewayTxnId(), row.completedAt());
    }

    private PaymentRow load(CustomerAccount account, String reference) {
        return payments.findByReference(account.accountId(), reference)
                .orElseThrow(() -> new NotFoundException(
                        "Payment " + reference + " not found in account " + account.accountId()));
    }

    private CustomerAccount myAccount() {
        long customerId = scope.requireCustomerId();
        return accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"));
    }

    private static Invoice toInvoice(InvoiceRow row) {
        BigDecimal outstanding = row.amountDue().subtract(row.amountPaid()).max(BigDecimal.ZERO);
        return new Invoice(row.id(), row.invoiceRef(), row.periodStart(), row.periodEnd(), row.issueDate(),
                row.dueDate(), money(row.amountDue()), money(row.amountPaid()), money(outstanding),
                InvoiceStatus.valueOf(row.status()));
    }

    private static Payment toPayment(PaymentRow row) {
        PaymentStatus status = PaymentStatus.valueOf(row.status());
        return new Payment(row.reference(), row.invoiceRef(), money(row.amount()), row.currency(), method(row.method()),
                status, row.failureReason(), row.initiatedAt(), row.completedAt(), status == PaymentStatus.SUCCEEDED);
    }

    private static Method method(String value) {
        return value == null ? null : Method.valueOf(value);
    }

    /** {@code Money}: two fraction digits, plain notation. */
    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
