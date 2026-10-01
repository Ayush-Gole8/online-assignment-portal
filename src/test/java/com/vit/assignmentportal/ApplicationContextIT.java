package com.vit.assignmentportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Milestone 1 integration test required by CLAUDE.md §13: it proves the Spring context
 * starts, that Flyway applies the baseline migration, and that JPA is wired against a real
 * MySQL 8.4.
 *
 * <p>A real MySQL rather than H2, because the point is to exercise the engine that ships
 * (ADR-003, docs/testing/strategy.md §3.2). H2 would pass against a dialect that never runs
 * in production and would hide a broken migration.
 *
 * <p>Datasource properties are supplied through {@link DynamicPropertySource}, which takes
 * precedence over {@code application.yml}. That is deliberate: it prevents application.yml's
 * {@code ${DB_PASSWORD}} placeholder from being resolved, so the test needs no environment
 * variables and no secret is written into the test sources.
 *
 * <p>Requires a running Docker daemon (Testcontainers) — see docs/operations/toolchain.md §4.
 */
@SpringBootTest
@Testcontainers
class ApplicationContextIT {

    private static final String INSERT_USER = """
            INSERT INTO users (username, password_hash, display_name, role, enabled, created_at)
            VALUES (?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6))
            """;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Fails if any bean, Flyway migration, or JPA mapping cannot be constructed.
    }

    @Test
    void appliesBaselineMigration_andCreatesUsersTable() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name = 'users'
                """, Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    @Test
    void acceptsAValidRole_andRejectsAnUnknownOne() {
        // A CHECK constraint that is never exercised is a constraint that may not exist
        // (ADR-003, docs/testing/strategy.md §3.2).
        jdbcTemplate.update(INSERT_USER, "constraint-probe", "$2a$10$notarealhash",
                "Constraint Probe", "STUDENT");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, "constraint-probe"))
                .isEqualTo(1);

        Throwable thrown = catchThrowable(() ->
                jdbcTemplate.update(INSERT_USER,
                        "invalid-role-user",
                        "hash",
                        "Invalid Role User",
                        "INVALID_ROLE"
                )
        );
        
        assertThat(thrown)
                .isInstanceOf(DataAccessException.class);
        
        assertThat(thrown.getMessage())
                .contains("ck_users_role")
                .contains("violated");
    }

}