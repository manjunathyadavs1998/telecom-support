package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Design 03-inbound backend §2, §9: V7 account suspension CHECK and the {@link AbstractMySqlIT} suspension helpers.
 * Each test runs in a rolled-back transaction, so the rows it changes never reach other ITs.
 */
@Transactional
class SchemaV7toV9IT extends AbstractMySqlIT {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    // ---------------------------------------------------------------- V7 accounts suspension

    @Test
    void suspendedAccountNeedsReasonAndTimeAndOnlySuspendedHasThem() {
        // SUSPENDED without reason/time
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET status = 'SUSPENDED' WHERE account_ref = 'ACC-T-0001'", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        // SUSPENDED with a reason but no time
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET status = 'SUSPENDED', suspension_reason = 'NON_PAYMENT'
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        // ACTIVE and CLOSED never carry a reason
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET suspension_reason = 'NON_PAYMENT', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET suspension_reason = 'OTHER', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0003'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
    }

    @Test
    void suspensionReasonIsOneOfTheSevenCodes() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET status = 'SUSPENDED', suspension_reason = 'UNPAID', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension_reason");

        for (String reason : new String[] {"NON_PAYMENT", "CUSTOMER_REQUEST", "KYC_PENDING", "FRAUD", "LEGAL",
                "REGULATORY", "OTHER"}) {
            suspendAccount("ACC-T-0001", reason);
            assertThat(suspension("ACC-T-0001")).isEqualTo("SUSPENDED/" + reason + "/true");
        }
    }

    @Test
    void helpersSuspendAndReinstateInOneStatement() {
        suspendAccount("ACC-T-0002", "NON_PAYMENT");
        assertThat(suspension("ACC-T-0002")).isEqualTo("SUSPENDED/NON_PAYMENT/true");

        reinstateAccount("ACC-T-0001", "ACC-T-0002");
        assertThat(suspension("ACC-T-0001")).isEqualTo("ACTIVE/-/false");
        assertThat(suspension("ACC-T-0002")).isEqualTo("ACTIVE/-/false");
    }

    @Test
    void testDataHasNoSuspendedAccountWithoutAReason() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts
                WHERE (status = 'SUSPENDED') <> (suspension_reason IS NOT NULL AND suspended_at IS NOT NULL)
                """, Map.of(), Long.class)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private String suspension(String accountRef) {
        return jdbc.queryForObject("""
                SELECT CONCAT(status, '/', COALESCE(suspension_reason, '-'), '/',
                              IF(suspended_at IS NULL, 'false', 'true'))
                FROM accounts WHERE account_ref = :ref
                """, Map.of("ref", accountRef), String.class);
    }
}
