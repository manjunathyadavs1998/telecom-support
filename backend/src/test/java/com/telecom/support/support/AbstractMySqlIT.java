package com.telecom.support.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/test-data")
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
}
