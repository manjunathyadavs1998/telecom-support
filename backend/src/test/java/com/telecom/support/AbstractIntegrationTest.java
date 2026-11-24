package com.telecom.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests: one MySQL 8.4 container shared by every test class in the JVM
 * (singleton pattern), wired through {@link ServiceConnection}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("telecom_support")
            .withCommand("--log-bin-trust-function-creators=1")
            .withReuse(true);

    static {
        MYSQL.start();
    }
}
