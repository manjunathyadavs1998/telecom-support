package com.telecom.support.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.SuspensionReason;
import com.telecom.support.identity.AppUserDetailsService;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.support.AbstractMySqlIT;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link CustomerScope} and {@link AccountLookup} (design 02-customer backend §4, R1, R16, §9; BR-10, CUS-01.3): the
 * customer id comes only from the principal; staff, unlinked customers and anonymous callers are not customers.
 */
class CustomerScopeIT extends AbstractMySqlIT {

    @Autowired
    CustomerScope scope;

    @Autowired
    AccountLookup accounts;

    @Autowired
    AppUserDetailsService users;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopeIsTheBeanNamedCustomerScopeForPreAuthorize() {
        assertThat(context.getBean("customerScope")).isSameAs(scope);
    }

    @Test
    void linkedCustomerGetsItsOwnCustomerIdAndAccount() {
        signIn("+919900000001");

        assertThat(scope.isCustomer()).isTrue();
        long customerId = scope.requireCustomerId();
        assertThat(customerId).isEqualTo(customerIdOf("CUST-T-0001"));

        CustomerAccount account = accounts.accountFor(customerId).orElseThrow();
        assertThat(account.accountRef()).isEqualTo("ACC-T-0001");
        assertThat(account.customerId()).isEqualTo(customerId);
        assertThat(account.accountId()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM accounts WHERE account_ref = 'ACC-T-0001'", Map.of(), Long.class));
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.customerActive()).isTrue();
        assertThatCode(account::requireMutable).doesNotThrowAnyException();
    }

    @Test
    void otherLinkedCustomerGetsItsOwnIdNotTheFirstOne() {
        signIn("+919900000002");

        assertThat(scope.requireCustomerId()).isEqualTo(customerIdOf("CUST-T-0002"));
        assertThat(accounts.accountFor(scope.requireCustomerId()).orElseThrow().accountRef()).isEqualTo("ACC-T-0002");
    }

    @Test
    void unlinkedCustomerIsNotACustomer() {
        signIn("+919900000004");

        assertThat(scope.isCustomer()).isFalse();
        assertThatThrownBy(scope::requireCustomerId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffIsNotACustomer() {
        signIn("T-IN");

        assertThat(scope.isCustomer()).isFalse();
        assertThatThrownBy(scope::requireCustomerId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousIsNotACustomer() {
        assertThat(scope.isCustomer()).isFalse();
        assertThatThrownBy(scope::requireCustomerId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void closedAccountRejectsMutations() {
        signIn("+919900000003");

        CustomerAccount account = accounts.accountFor(scope.requireCustomerId()).orElseThrow();
        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(account::requireMutable).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void inactiveCustomerRejectsMutations() {
        CustomerAccount account = new CustomerAccount(1L, 1L, "ACC-X", AccountStatus.ACTIVE, false, null, null);
        assertThatThrownBy(account::requireMutable).isInstanceOf(BusinessRuleException.class);
        CustomerAccount suspended = new CustomerAccount(1L, 1L, "ACC-X", AccountStatus.SUSPENDED, true,
                SuspensionReason.OTHER, java.time.Instant.EPOCH);
        assertThatCode(suspended::requireMutable).doesNotThrowAnyException();
    }

    @Test
    void unknownCustomerHasNoAccount() {
        assertThat(accounts.accountFor(-1L)).isEmpty();
    }

    private void signIn(String username) {
        CurrentUser user = users.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private long customerIdOf(String customerRef) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :ref", Map.of("ref", customerRef),
                Long.class);
    }
}
