package com.vit.assignmentportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The integration test required by CLAUDE.md §13: it proves the Spring context starts, that
 * Flyway applies the baseline migration, and that the database-level constraints the schema
 * declares actually exist.
 *
 * <p>These are the assertions a unit test cannot make and a schema review can only assume. A
 * {@code CHECK} constraint that is never exercised is a constraint that may not exist — it is
 * one typo away from being a comment (ADR-003, docs/testing/strategy.md §3.2).
 */
@SpringBootTest
class ApplicationContextIT extends MySqlIntegrationTest {

    /**
     * Inserts a row without going through JPA, so the constraints under test are the database's
     * rather than Hibernate's.
     */
    private static final String INSERT_USER = """
            INSERT INTO users (id, email, password_hash, role, enabled, created_at, updated_at)
            VALUES (?, ?, ?, ?, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Fails if any bean, Flyway migration, or JPA mapping cannot be constructed. In
        // particular this is where a mismatch between the User entity and the migrated table
        // surfaces: ddl-auto is `validate`, so Hibernate refuses to start rather than silently
        // altering the schema (ADR-003).
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
        jdbcTemplate.update(INSERT_USER, UUID.randomUUID().toString(), "constraint-probe@example.com",
                "$2a$10$notarealhash", "STUDENT");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class,
                "constraint-probe@example.com"))
                .isEqualTo(1);

        Throwable thrown = catchThrowable(() ->
                jdbcTemplate.update(INSERT_USER, UUID.randomUUID().toString(),
                        "invalid-role@example.com",
                        "$2a$10$notarealhash",
                        "INVALID_ROLE"));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage())
                .contains("ck_users_role")
                .contains("violated");
    }

    /**
     * The unique index on {@code email} rejects a duplicate — and does so case-insensitively,
     * because the column collation is {@code utf8mb4_0900_ai_ci}.
     *
     * <p>The case-insensitivity is the part worth proving. It is what stops "Student@example.com"
     * and "student@example.com" becoming two accounts for one person, and it is a property of the
     * schema rather than of any Java code — so it would survive a rewrite of the service layer
     * and must therefore be asserted against the database.
     */
    @Test
    void rejectsADuplicateEmail_evenWhenTheCaseDiffers() {
        String email = "unique-probe@example.com";
        jdbcTemplate.update(INSERT_USER, UUID.randomUUID().toString(), email,
                "$2a$10$notarealhash", "STUDENT");

        Throwable thrown = catchThrowable(() ->
                jdbcTemplate.update(INSERT_USER, UUID.randomUUID().toString(),
                        "UNIQUE-PROBE@example.com",
                        "$2a$10$notarealhash",
                        "REVIEWER"));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("uq_users_email");
    }

    /**
     * An identifier that is not a UUID cannot be stored. The column is {@code VARCHAR(36)}, so
     * this is a length assertion rather than a type assertion — MySQL will accept any 36-character
     * string. It documents the column's width, which is what the entity's converter relies on.
     */
    @Test
    void storesTheIdentifierAsAFixedWidthString() {
        Integer width = jdbcTemplate.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'users'
                   AND column_name = 'id'
                """, Integer.class);

        assertThat(width).isEqualTo(36);
    }
}
