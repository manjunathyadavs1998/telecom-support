package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** B2: the IT context starts on Testcontainers with V1 + V2 + test data and without the dev seed (design §2, §3.1). */
class SchemaIT extends AbstractMySqlIT {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void appliesVersionedMigrationsAndTestDataButNotTheDevSeed() {
        List<String> applied = jdbc.queryForList("""
                SELECT script FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank
                """, Map.of(), String.class);

        assertThat(applied).containsExactly(
                "V1__baseline.sql", "V2__identity_rbac_audit.sql", "V3__catalog.sql", "V4__customer.sql",
                "V5__servicecase.sql", "V6__payment.sql", "V7__account_suspension.sql", "V8__inbound.sql",
                "V9__reactivation_notification.sql", "V10__agent_kba_windows.sql", "V11__outbound.sql",
                "R__test_users.sql", "R__test_users_02_customer.sql", "R__test_users_03_inbound.sql",
                "R__test_users_04_outbound.sql");
        assertThat(count("SELECT COUNT(*) FROM users WHERE username = 'IN001'")).isZero();
    }

    @Test
    void testDataHasOneUserPerRoleCombination() {
        List<String> combos = jdbc.queryForList("""
                SELECT CONCAT(u.username, '=', GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR '+'))
                FROM users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN roles r ON r.id = ur.role_id
                GROUP BY u.username
                ORDER BY u.username
                """, Map.of(), String.class);

        assertThat(combos).containsExactlyInAnyOrder(
                "+919900000001=CUSTOMER",
                "+919900000002=CUSTOMER",   // slice 02 test data (R__test_users_02_customer.sql)
                "+919900000003=CUSTOMER",
                "+919900000004=CUSTOMER",
                "T-IN=INBOUND_AGENT",
                "T-IN-REACT=INBOUND_AGENT+REACTIVATION_APPROVER",
                "T-OUT=OUTBOUND_AGENT",
                "T-OUT-2=OUTBOUND_AGENT",   // slice 04 test data (R__test_users_04_outbound.sql): same team, parallel claims
                "T-OUT-S=OUTBOUND_AGENT",   // slice 04: OUTBOUND_S, queue scope
                "T-OUT-SUP=OUTBOUND_AGENT+OUTBOUND_SUPERVISOR",
                "T-BUS=BUSINESS_ANALYST",
                "T-BUS-AUD=BUSINESS_ANALYST+BUSINESS_AUDITOR");
    }

    @Test
    void seedsSevenRolesAndTwentySixPermissions() {
        assertThat(count("SELECT COUNT(*) FROM roles")).isEqualTo(7);
        // 25 (V2) + case.severity.override (V8, 01 §13 E3)
        assertThat(count("SELECT COUNT(*) FROM permissions")).isEqualTo(26);
    }

    @Test
    void customerWithTeamIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (username, password_hash, display_name, user_type, team_id, status,
                                   created_at, updated_at)
                VALUES ('+919900000099', 'x', 'Bad Customer', 'CUSTOMER',
                        (SELECT id FROM teams WHERE code = 'INBOUND'), 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_users_customer_no_team");
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
