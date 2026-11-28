package com.telecom.support.support;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests (design 01-foundation backend §8).
 *
 * <p>One MySQL 8.4 container is shared by every IT class in the JVM and wired in through {@link ServiceConnection}.
 * Flyway applies the versioned migrations and then the repeatable test data from {@code db/test-data}; the dev
 * seed in {@code db/seed} is never loaded. {@link #mvc} drives the full servlet filter chain (correlation id + security).
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/test-data",
        // PaymentReconciliationJob runs only when a test calls it (it polls every PENDING payment)
        "app.payment.reconcile.pending-cron=-", "app.payment.reconcile.late-capture-cron=-",
                // in-memory notification sender and provisioning; their jobs run only when a test calls them
                "app.notification.mock.enabled=true", "app.notification.dispatch-cron=-",
                "app.reactivation.provisioning.mock.enabled=true", "app.reactivation.reconcile-cron=-",
                "app.reactivation.expiry-cron=-",
                // IdentityCheckCleanupJob runs only when a test calls run() (review C5: no wall-clock purges mid-test)
                "app.inbound.identity-check.cleanup-cron=-",
                // CaseAutoCloseJob runs only when a test calls run() (04 backend §3.2)
                "app.outbound.auto-close-cron=-"})
public abstract class AbstractMySqlIT {

    /** Password of every user in {@code db/test-data/R__test_users.sql}. */
    public static final String TEST_PASSWORD = "Password@123";

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("telecom_support")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    // the app user (no SUPER) creates the audit triggers while binary logging is on
                    "--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    private NamedParameterJdbcTemplate accountsJdbc;

    /**
     * Suspends an account with a reason (design 03-inbound backend §2): status, {@code suspension_reason} and
     * {@code suspended_at} change in one statement, as the V7 CHECK requires.
     */
    protected void suspendAccount(String accountRef, String reason) {
        accountsJdbc.update("""
                UPDATE accounts
                SET status = 'SUSPENDED', suspension_reason = :reason, suspended_at = UTC_TIMESTAMP(6),
                    updated_at = UTC_TIMESTAMP(6)
                WHERE account_ref = :ref
                """, Map.of("ref", accountRef, "reason", reason));
    }

    /** Sets the accounts ACTIVE and clears their suspension reason and time in the same statement (V7 CHECK). */
    protected void reinstateAccount(String... accountRefs) {
        accountsJdbc.update("""
                UPDATE accounts
                SET status = 'ACTIVE', suspension_reason = NULL, suspended_at = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE account_ref IN (:refs)
                """, Map.of("refs", List.of(accountRefs)));
    }
}
