package com.telecom.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.NotificationContacts;
import com.telecom.support.customer.AccountLookup.SuspensionReason;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.support.AbstractMySqlIT;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-module APIs added for slice 03 (design 03-inbound backend §6.1, B5): {@link UserDirectory} (identity) and the
 * {@link AccountLookup} additions (customer). Reads the 03 test data ({@code R__test_users_03_inbound.sql}); any write
 * is rolled back.
 */
class CrossModuleApiIT extends AbstractMySqlIT {

    @Autowired
    private UserDirectory directory;

    @Autowired
    private AccountLookup accounts;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    // ------------------------------------------------------------------ UserDirectory

    @Test
    void displayNamesSkipNullAndUnknownIds() {
        long in = userId("T-IN");
        long out = userId("T-OUT");

        Map<Long, String> names = directory.displayNames(Arrays.asList(in, out, in, null, -1L));

        assertThat(names).containsExactlyInAnyOrderEntriesOf(
                Map.of(in, "Test Inbound Agent", out, "Test Outbound Agent"));
        assertThat(directory.displayNames(List.of())).isEmpty();
    }

    @Test
    void teamNamesSkipNullAndUnknownIds() {
        long inbound = teamId("INBOUND");
        String name = jdbc.queryForObject("SELECT name FROM teams WHERE id = :id", Map.of("id", inbound), String.class);

        assertThat(directory.teamNames(Arrays.asList(inbound, null, -1L))).containsExactly(Map.entry(inbound, name));
        assertThat(directory.teamNames(List.of())).isEmpty();
    }

    @Test
    void userLockNeedsATransactionAndHoldsTheRow() {
        long agent = userId("T-IN-REACT");
        String probe = "SELECT id FROM users WHERE id = :id FOR UPDATE NOWAIT";
        assertThatThrownBy(() -> directory.lockForUpdate(agent)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> directory.lockForUpdate(-1L)))
                .isInstanceOf(NotFoundException.class);

        tx.executeWithoutResult(s -> {
            directory.lockForUpdate(agent);
            assertThat(lockedElsewhere(probe, agent)).isTrue();
        });
        assertThat(lockedElsewhere(probe, agent)).isFalse();
    }

    // ------------------------------------------------------------------ AccountLookup

    @Test
    void accountCarriesSuspensionReasonAndTime() {
        CustomerAccount suspended = accounts.accountFor(customerId("CUST-T-0007")).orElseThrow();
        assertThat(suspended.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(suspended.suspensionReason()).isEqualTo(SuspensionReason.FRAUD);
        assertThat(suspended.suspendedAt()).isNotNull();

        CustomerAccount active = accounts.accountFor(customerId("CUST-T-0008")).orElseThrow();
        assertThat(active.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(active.customerActive()).isFalse();
        assertThat(active.suspensionReason()).isNull();
        assertThat(active.suspendedAt()).isNull();
    }

    @Test
    void accountLockNeedsATransactionAndHoldsTheRow() {
        long accountId = accountId("ACC-T-0006");
        String probe = "SELECT id FROM accounts WHERE id = :id FOR UPDATE NOWAIT";
        assertThatThrownBy(() -> accounts.lockForUpdate(accountId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> accounts.lockForUpdate(-1L)))
                .isInstanceOf(NotFoundException.class);

        tx.executeWithoutResult(s -> {
            CustomerAccount account = accounts.lockForUpdate(accountId);
            assertThat(account.accountRef()).isEqualTo("ACC-T-0006");
            assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
            assertThat(account.suspensionReason()).isEqualTo(SuspensionReason.NON_PAYMENT);
            assertThat(account.suspendedAt()).isNotNull();
            assertThat(account.customerActive()).isTrue();
            assertThat(lockedElsewhere(probe, accountId)).isTrue();
        });
        assertThat(lockedElsewhere(probe, accountId)).isFalse();
    }

    @Test
    void suspendedMsisdnsArePrimaryFirstAndOnlySuspended() {
        assertThat(accounts.suspendedMsisdns(accountId("ACC-T-0006")))
                .containsExactly("+919900000006", "+919900000016");
        assertThat(accounts.suspendedMsisdns(accountId("ACC-T-0008"))).isEmpty();
        assertThat(accounts.suspendedMsisdns(-1L)).isEmpty();
    }

    @Test
    void notificationContactsAreThePrimaryMsisdnAndOnlyAVerifiedEmail() {
        assertThat(accounts.notificationContacts(accountId("ACC-T-0006"))).isEqualTo(new NotificationContacts(
                customerId("CUST-T-0006"), "+919900000006", "test.customer6@example.test"));
        // CUST-T-0007 has only a verified secondary MSISDN: no email destination
        assertThat(accounts.notificationContacts(accountId("ACC-T-0007"))).isEqualTo(new NotificationContacts(
                customerId("CUST-T-0007"), "+919900000007", null));
        assertThatThrownBy(() -> accounts.notificationContacts(-1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unverifiedEmailIsNotANotificationContact() {
        long customer = customerId("CUST-T-0006");
        long account = accountId("ACC-T-0006");
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customers SET email_verified_at = NULL WHERE id = :id", Map.of("id", customer));
            assertThat(accounts.notificationContacts(account).verifiedEmail()).isNull();
            s.setRollbackOnly();
        });
        assertThat(accounts.notificationContacts(account).verifiedEmail()).isEqualTo("test.customer6@example.test");
    }

    // ------------------------------------------------------------------ helpers

    /** True when another connection cannot take the row lock right now (MySQL {@code NOWAIT}). */
    private boolean lockedElsewhere(String sql, long id) {
        return CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            try {
                jdbc.queryForList(sql, Map.of("id", id), Long.class);
                return false;
            } catch (DataAccessException e) {
                return true;
            }
        })).join();
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = :u", Map.of("u", username), Long.class);
    }

    private long teamId(String code) {
        return jdbc.queryForObject("SELECT id FROM teams WHERE code = :c", Map.of("c", code), Long.class);
    }

    private long customerId(String ref) {
        return jdbc.queryForObject("SELECT id FROM customers WHERE customer_ref = :r", Map.of("r", ref), Long.class);
    }

    private long accountId(String ref) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_ref = :r", Map.of("r", ref), Long.class);
    }
}
