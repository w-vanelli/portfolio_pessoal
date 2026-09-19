package com.wvanelli.ledgerstream;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests using a real PostgreSQL 16 instance via Testcontainers.
 *
 * <p>Architecture:
 * <ul>
 *   <li>A single static {@link PostgreSQLContainer} is shared across all test classes extending this base,
 *       started once per JVM lifecycle (Testcontainers "singleton" pattern). This is compatible with
 *       Spring's test context caching — all {@code @SpringBootTest} classes sharing the same configuration
 *       will reuse the same application context and the same database container.</li>
 *   <li>Flyway executes all migrations (V1, V2, ...) against the container's database, creating the
 *       schema from scratch.</li>
 *   <li>Hibernate is set to {@code ddl-auto: validate}, ensuring that JPA mappings are validated
 *       against the Flyway-created schema rather than generating or altering it.</li>
 *   <li>RabbitMQ auto-configuration is excluded via the {@code integration-test} profile to avoid
 *       requiring an additional container in this phase.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledgerstream_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("localhost", "127.0.0.1"));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
