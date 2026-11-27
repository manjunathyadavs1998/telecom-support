package com.telecom.support.customer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.TooManyRequestsException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.IdentityCheckRepository.CustomerFacts;
import com.telecom.support.customer.IdentityCheckRepository.Window;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Review C4: a 429 decided under the customer row lock is audited only after that transaction has rolled back (the
 * lock is released), so the audit's own pool connection is never requested while the lock is held.
 */
class IdentityCheckRateLimitAuditTest {

    private static final long CUSTOMER = 6L;
    private static final Instant NOW = Instant.parse("2030-01-15T10:00:00Z");

    private final IdentityCheckRepository checks = mock(IdentityCheckRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerLookupService lookups = mock(CustomerLookupService.class);
    private final OtpCodec codec = mock(OtpCodec.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorProvider actors = mock(ActorProvider.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private IdentityCheckService service;

    @BeforeEach
    void setUp() {
        when(actors.currentActor()).thenReturn(Optional.of(new Actor(42L, "T-IN")));
        when(lookups.isValidToken(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(customers.lockForUpdate(CUSTOMER)).thenReturn(true);
        when(checks.findFacts(CUSTOMER)).thenReturn(Optional.of(new CustomerFacts(CUSTOMER, "CUST-T-0006",
                "+919900000006", "560001", "c6@example.test", null)));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(codec.newCode()).thenReturn("123456");
        when(codec.hmac(anyString())).thenReturn("hmac");
        service = new IdentityCheckService(checks, customers, lookups, mock(VerificationCodeSender.class), codec,
                audit, actors, new OtpProperties(null, null, null, null, null),
                new InboundProperties(null, null, null, null, null, null), Clock.fixed(NOW, ZoneOffset.UTC),
                transactions);
    }

    @Test
    void anOtpStartLimitIsAuditedAfterTheLockingTransactionRolledBack() {
        when(checks.otpStartsSince(eq(CUSTOMER), any())).thenReturn(new Window(5, Optional.of(NOW)));

        assertThatThrownBy(() -> service.startOtp(CUSTOMER, "token")).isInstanceOf(TooManyRequestsException.class);

        assertAuditedAfterRollback();
    }

    @Test
    void aKbaLimitIsAuditedAfterTheLockingTransactionRolledBack() {
        when(checks.kbaAttemptsOfCustomerSince(eq(CUSTOMER), any())).thenReturn(new Window(3, Optional.of(NOW)));

        assertThatThrownBy(() -> service.startKba(CUSTOMER, "token", "560001", "c6@example.test"))
                .isInstanceOf(TooManyRequestsException.class);

        assertAuditedAfterRollback();
    }

    private void assertAuditedAfterRollback() {
        InOrder order = inOrder(customers, transactions, audit);
        order.verify(customers).lockForUpdate(CUSTOMER);
        order.verify(transactions).rollback(any());
        order.verify(audit).recordIndependently(eq(IdentityCheckService.FAILED), eq(Outcome.FAILURE), eq(42L),
                eq("T-IN"), eq(CustomerLookupService.ENTITY_CUSTOMER), eq("CUST-T-0006"), any());
    }
}
