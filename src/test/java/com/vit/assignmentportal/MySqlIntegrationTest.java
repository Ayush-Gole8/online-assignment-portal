package com.vit.assignmentportal;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Base class for the integration tests that need a real MySQL.
 *
 * <p>Every integration test in this project asserts something that only the real engine can
 * prove — Flyway migrations, a {@code CHECK} constraint, a unique index that is
 * case-insensitive because of its collation, JPA validation of an entity against a migrated
 * table. H2 would pass against a dialect that never runs in production and would hide a broken
 * migration (ADR-003, docs/testing/strategy.md §3.2).
 *
 * <h2>One container for the whole suite, not one per class</h2>
 *
 * <p>The container is started in a static initialiser and never stopped by the test framework,
 * so the JVM starts it once and every integration test class shares it. The alternative — the
 * {@code @Testcontainers} / {@code @Container} pair on each class — starts a fresh MySQL for
 * every class, which costs a container start per test file and adds nothing: the tests are
 * isolated by using distinct data, not by using distinct servers.
 *
 * <p>Testcontainers' reaper (Ryuk) removes the container when the JVM exits, so "never stopped"
 * does not mean "never cleaned up". Ryuk is enabled by default.
 *
 * <p>Docker must be running. When it is not, the static initialiser fails and the failure names
 * the container that could not start, rather than a {@code NullPointerException} from a
 * datasource that was never configured.
 *
 * <h2>Why not {@code @ServiceConnection}</h2>
 *
 * <p>{@link DynamicPropertySource} bypasses {@code application.yml} entirely, which is the point:
 * the placeholder {@code ${DB_PASSWORD}} is never resolved, so the suite needs no environment
 * variables and no secret appears in the test sources. See this class's own rule in
 * {@code SECURITY.md} — a credential in a test file is still a committed credential.
 */
public abstract class MySqlIntegrationTest {

    /**
     * Pinned to the production major and minor version. Testcontainers resolves {@code mysql:8.4}
     * to the newest patch, so the pipeline exercises the same engine family that ships without
     * pinning a patch level that would need bumping by hand.
     *
     * <p>Note the import and the absence of a type parameter: {@code org.testcontainers.mysql},
     * not the older {@code org.testcontainers.containers}. Testcontainers 2.x moved each
     * database's container into the module that owns it, and the class there is no longer generic
     * — the self-typed {@code MySQLContainer<SELF>} of 1.x was replaced by a plain class, so
     * {@code MySQLContainer<?>} does not compile. The {@code containers} package still carries a
     * deprecated copy that emits a build warning.
     */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
