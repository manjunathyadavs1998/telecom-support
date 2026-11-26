package com.telecom.support.customer;

import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Gives other modules (servicecase, payment) a customer's account id, reference and state (design 02-customer backend
 * §4, R1, R16) without reaching into {@code customer} repositories. The customer id passed in must come from
 * {@code CustomerScope}, never from the request.
 */
@Service
public class AccountLookup {

    public enum AccountStatus { ACTIVE, SUSPENDED, CLOSED }

    /** The account of a customer and its state; {@code customerActive} is {@code customers.status = ACTIVE}. */
    public record CustomerAccount(long accountId, long customerId, String accountRef, AccountStatus status,
                                  boolean customerActive) {

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

    private final AccountRepository accounts;

    AccountLookup(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** The customer's account, or empty when the customer has none. */
    public Optional<CustomerAccount> accountFor(long customerId) {
        return accounts.findByCustomerId(customerId).map(row -> new CustomerAccount(row.id(), row.customerId(),
                row.accountRef(), AccountStatus.valueOf(row.status()), "ACTIVE".equals(row.customerStatus())));
    }
}
