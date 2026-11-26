package com.telecom.support.customer;

import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.customer.AccountRepository.AccountRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives other modules (servicecase, payment, reactivation) a customer's account id, reference and state (design
 * 02-customer backend §4, R1, R16; 03-inbound backend §6.1) without reaching into {@code customer} repositories. A
 * customer id passed in must come from {@code CustomerScope} or a {@code VerifiedCustomer}, never from the request;
 * an account id comes from such an account or from a stored reactivation request (jobs, no agent).
 */
@Service
public class AccountLookup {

    public enum AccountStatus { ACTIVE, SUSPENDED, CLOSED }

    /** {@code accounts.suspension_reason} (03 §2); set exactly when the account is SUSPENDED. */
    public enum SuspensionReason { NON_PAYMENT, CUSTOMER_REQUEST, KYC_PENDING, FRAUD, LEGAL, REGULATORY, OTHER }

    /**
     * The account of a customer and its state; {@code customerActive} is {@code customers.status = ACTIVE}.
     * {@code suspensionReason} and {@code suspendedAt} (UTC) are non-null exactly when {@code status} is SUSPENDED.
     */
    public record CustomerAccount(long accountId, long customerId, String accountRef, AccountStatus status,
                                  boolean customerActive, SuspensionReason suspensionReason, Instant suspendedAt) {

        /**
         * R16: an INACTIVE customer or a CLOSED account allows no mutation (422 {@code business-rule}). A SUSPENDED
         * account is not rejected here; the plan-change and promotion-claim rules check that themselves.
         */
        public void requireMutable() {
            if (!customerActive || status == AccountStatus.CLOSED) {
                throw new BusinessRuleException(
                        "Mutation on account " + accountId + " rejected: status=" + status + ", customerActive="
                                + customerActive,
                        "This account is closed or inactive, so it cannot be changed.");
            }
        }
    }

    /**
     * Where a customer notification may go (03 R16): the primary MSISDN (null when there is none) and the email only
     * when verified (else null).
     */
    public record NotificationContacts(long customerId, String primaryMsisdn, String verifiedEmail) {}

    private final AccountRepository accounts;
    private final CustomerRepository customers;

    AccountLookup(AccountRepository accounts, CustomerRepository customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    /** The customer's account, or empty when the customer has none. */
    public Optional<CustomerAccount> accountFor(long customerId) {
        return accounts.findByCustomerId(customerId).map(AccountLookup::toAccount);
    }

    /**
     * 03 R14 and the reactivation jobs: locks the account row until the caller's transaction ends (callers that also
     * lock a {@code users} row lock it first) and returns the account as of the lock. An unknown id is 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerAccount lockForUpdate(long accountId) {
        return accounts.lockById(accountId).map(AccountLookup::toAccount)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }

    /** 03 R13/R14: the MSISDNs of the account's SUSPENDED subscriptions, primary first; empty when none. */
    public List<String> suspendedMsisdns(long accountId) {
        return accounts.findSuspendedMsisdns(accountId);
    }

    /** 03 R16: the notification destinations of the account's customer. An unknown id is 404. */
    public NotificationContacts notificationContacts(long accountId) {
        return customers.findContactsByAccount(accountId)
                .map(row -> new NotificationContacts(row.customerId(), row.primaryMsisdn(), row.verifiedEmail()))
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
    }

    private static CustomerAccount toAccount(AccountRow row) {
        return new CustomerAccount(row.id(), row.customerId(), row.accountRef(), AccountStatus.valueOf(row.status()),
                "ACTIVE".equals(row.customerStatus()),
                row.suspensionReason() == null ? null : SuspensionReason.valueOf(row.suspensionReason()),
                row.suspendedAt());
    }
}
