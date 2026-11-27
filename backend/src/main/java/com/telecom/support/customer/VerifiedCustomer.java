package com.telecom.support.customer;

/**
 * Proof that the calling inbound agent holds a valid identity check for a customer (design 03-inbound backend R5, §6.1).
 * Only {@link VerifiedCustomerAccess} creates it (package-private constructor), and {@link VerifiedCustomerInterceptor}
 * hands it to handlers of {@code /api/v1/agent/customers/{customerId}/**}. Every by-customer API takes this type, never a
 * raw customer id, so a caller cannot reach a customer's data without a check.
 */
public final class VerifiedCustomer {

    private final long customerId;
    private final long accountId;
    private final long checkId;
    private final long agentUserId;

    VerifiedCustomer(long customerId, long accountId, long checkId, long agentUserId) {
        this.customerId = customerId;
        this.accountId = accountId;
        this.checkId = checkId;
        this.agentUserId = agentUserId;
    }

    public long customerId() {
        return customerId;
    }

    public long accountId() {
        return accountId;
    }

    /** The VERIFIED identity check that grants the access (audit evidence). */
    public long checkId() {
        return checkId;
    }

    public long agentUserId() {
        return agentUserId;
    }

    @Override
    public String toString() {
        return "VerifiedCustomer[customerId=" + customerId + ", accountId=" + accountId + ", checkId=" + checkId
                + ", agentUserId=" + agentUserId + "]";
    }
}
