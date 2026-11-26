package com.telecom.support.customer;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.VerificationRequiredException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.IdentityCheckRepository.CustomerFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Central verified-customer scope (design 03-inbound backend R5, R6, §6.1; INB-01.2, INB-01.4, BR-10, NFR-PRIV).
 *
 * <p>{@link #require(long)} grants access only with a check of the calling agent (from {@link ActorProvider}) for the
 * customer with {@code status=VERIFIED} and {@code valid_until > now}; otherwise 403 {@code verification-required}. The
 * customer's existence is not checked first, so an unknown id looks the same. Validity is never extended.
 *
 * <p>{@link #logFirstAccess} writes {@code CUSTOMER_RECORD_ACCESSED} exactly once per check: a guarded update of
 * {@code access_logged_at} and the audit row in one {@code REQUIRES_NEW} transaction, only when the update changed a row.
 */
@Service
public class VerifiedCustomerAccess {

    static final String RECORD_ACCESSED = "CUSTOMER_RECORD_ACCESSED";
    static final int MAX_PATH_LENGTH = 255;

    private final IdentityCheckRepository checks;
    private final AuditService audit;
    private final ActorProvider actors;
    private final Clock clock;
    private final TransactionTemplate newTx;

    VerifiedCustomerAccess(IdentityCheckRepository checks, AuditService audit, ActorProvider actors, Clock clock,
                           PlatformTransactionManager transactions) {
        this.checks = checks;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** R5: the calling agent's valid verification for the customer, else 403 {@code verification-required}. */
    public VerifiedCustomer require(long customerId) {
        Actor agent = actors.currentActor()
                .orElseThrow(() -> new VerificationRequiredException("No authenticated agent"));
        return checks.findVerified(agent.id(), customerId, clock.instant())
                .map(row -> new VerifiedCustomer(customerId, row.accountId(), row.checkId(), agent.id()))
                .orElseThrow(() -> new VerificationRequiredException(
                        "No valid identity check of agent " + agent.id() + " for customer " + customerId));
    }

    /** R6: the first protected read under the check writes one {@code CUSTOMER_RECORD_ACCESSED} row (once per check). */
    void logFirstAccess(VerifiedCustomer verified, String path) {
        Actor agent = actors.currentActor().orElseThrow();
        Instant now = clock.instant();
        newTx.executeWithoutResult(status -> {
            if (checks.markAccessLogged(verified.checkId(), now) != 1) {
                return;
            }
            String customerRef = checks.findFacts(verified.customerId()).map(CustomerFacts::customerRef).orElse(null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("checkId", verified.checkId());
            details.put("path", path != null && path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH)
                    : path);
            audit.record(RECORD_ACCESSED, agent.id(), agent.username(), CustomerLookupService.ENTITY_CUSTOMER,
                    customerRef, details);
        });
    }
}
