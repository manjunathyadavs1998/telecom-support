package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.SupportApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code dev,e2e} profile starts from a fresh database with the customer portal e2e seed (design 02-customer
 * backend erratum E5, frontend design D2) and returns from the mock gateway to the e2e origin (erratum E4). It runs on
 * its own schema in the shared container, so Flyway clean never touches the test database of the other ITs.
 */
class E2eProfileIT {

    private static final String SCHEMA = "telecom_e2e";

    @BeforeAll
    static void createSchema() throws Exception {
        // Testcontainers sets the root password to the app user's password
        try (Connection root = DriverManager.getConnection(url("mysql"), "root", AbstractMySqlIT.MYSQL.getPassword());
             Statement sql = root.createStatement()) {
            sql.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    @Test
    void everyStartCleansMigratesAndSeedsTheE2eData() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertSeed(jdbc);
            assertThat(context.getEnvironment().getProperty("app.payment.return-url-base"))
                    .isEqualTo("http://host.docker.internal:8080");
            // leave something behind that only a clean removes
            jdbc.update("INSERT INTO teams (code, name) VALUES ('E2E_LEFTOVER', 'Leftover')");
        }

        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE code = 'E2E_LEFTOVER'", Integer.class))
                    .isZero();
            assertSeed(jdbc);
        }
    }

    private static void assertSeed(JdbcTemplate jdbc) {
        // customer 1: >= 21 cases and >= 21 payments in the last 3 months (one SUCCEEDED), and an OPEN invoice
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM cases c JOIN accounts a ON a.id = c.account_id
                JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000001' AND c.created_at >= UTC_TIMESTAMP(6) - INTERVAL 3 MONTH""")
        ).isGreaterThanOrEqualTo(21);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM payments p JOIN accounts a ON a.id = p.account_id
                JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000001' AND p.initiated_at >= UTC_TIMESTAMP(6) - INTERVAL 3 MONTH""")
        ).isGreaterThanOrEqualTo(21);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM payments p JOIN accounts a ON a.id = p.account_id
                JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000001' AND p.status = 'SUCCEEDED'
                  AND p.initiated_at >= UTC_TIMESTAMP(6) - INTERVAL 3 MONTH""")
        ).isGreaterThanOrEqualTo(1);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM invoices i JOIN accounts a ON a.id = i.account_id
                JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000001' AND i.status = 'OPEN'""")
        ).isGreaterThanOrEqualTo(1);

        // customer 5: linked CUSTOMER login, exactly 2 OPEN invoices, no cases, no payments
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE u.username = '+919800000005' AND u.user_type = 'CUSTOMER' AND u.customer_id IS NOT NULL
                  AND r.code = 'CUSTOMER'""")
        ).isEqualTo(1);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM invoices i JOIN accounts a ON a.id = i.account_id
                JOIN users u ON u.customer_id = a.customer_id WHERE u.username = '+919800000005'""")
        ).isEqualTo(2);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM invoices i JOIN accounts a ON a.id = i.account_id
                JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000005' AND i.status = 'OPEN' AND i.amount_paid = 0""")
        ).isEqualTo(2);
        assertThat(count(jdbc, """
                SELECT (SELECT COUNT(*) FROM cases c WHERE c.account_id = a.id)
                     + (SELECT COUNT(*) FROM payments p WHERE p.account_id = a.id)
                FROM accounts a JOIN users u ON u.customer_id = a.customer_id
                WHERE u.username = '+919800000005'""")
        ).isZero();
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("dev", "e2e")
                .run("--DB_URL=" + url(SCHEMA),
                        "--DB_USERNAME=root",
                        "--DB_PASSWORD=" + AbstractMySqlIT.MYSQL.getPassword(),
                        "--server.port=0",
                        "--app.payment.reconcile.pending-cron=-",
                        "--app.payment.reconcile.late-capture-cron=-");
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + AbstractMySqlIT.MYSQL.getHost() + ":" + AbstractMySqlIT.MYSQL.getMappedPort(3306)
                + "/" + schema;
    }
}
