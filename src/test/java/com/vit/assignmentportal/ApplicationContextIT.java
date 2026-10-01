package com.vit.assignmentportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Timestamp;
import java.time.Instant;
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

    /**
     * Inserts a submission row directly, bypassing the entity and the service.
     *
     * <p>That is the point: {@code SubmissionService} checks the size, the status vocabulary, and
     * the shape of a decision before it reaches the database, so a test that went through it could
     * never discover whether the database would also have refused. The three {@code CHECK}
     * constraints below exist precisely to catch what a bug in that service would let through, and
     * the only way to prove they are there is to hand the database something the service would
     * never produce.
     */
    private static final String INSERT_SUBMISSION = """
            INSERT INTO submissions (id, student_id, title, course_code, original_file_name,
                                     stored_file_name, content_type, file_size_bytes,
                                     checksum_sha256, status, created_at, updated_at,
                                     reviewed_at, reviewer_id, version)
            VALUES (?, ?, 'Essay one', 'CS101', 'essay.pdf', ?, 'application/pdf', ?, ?, ?,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?, 0)
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

    // ------------------------------------------------------------------------
    // The submissions schema (V2)
    // ------------------------------------------------------------------------

    @Test
    void appliesTheSubmissionsMigration() {
        assertThat(tableExists("submissions")).isTrue();
    }

    /**
     * The database refuses a size the application would never have produced.
     *
     * <p>Three callers enforce the 10 MB limit — the servlet container, {@code
     * FileValidationService}, and this column — and this is the one that cannot be bypassed by a
     * code change. The lower bound is asserted too: a zero-byte row is what a bug that stored a
     * file before reading it would leave behind.
     */
    @Test
    void rejectsAFileSizeOutsideTheUploadLimit() {
        String studentId = aStudent();

        Throwable tooLarge = catchThrowable(() -> insertSubmission(studentId, oversizedSize()));
        assertThat(tooLarge).isInstanceOf(DataAccessException.class);
        assertThat(tooLarge.getMessage()).contains("ck_submissions_file_size");

        Throwable empty = catchThrowable(() -> insertSubmission(studentId, 0L));
        assertThat(empty).isInstanceOf(DataAccessException.class);
        assertThat(empty.getMessage()).contains("ck_submissions_file_size");
    }

    @Test
    void rejectsAStatusOutsideTheEnumVocabulary() {
        String studentId = aStudent();

        Throwable thrown = catchThrowable(() ->
                insertSubmission(studentId, "a-valid-name", 1024L, "PENDING", null, null));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("ck_submissions_status");
    }

    /**
     * A decision cannot be half-recorded.
     *
     * <p>{@code APPROVED} with no reviewer and no time is the state the review feature must be
     * unable to produce — it would mean an approval nobody is accountable for. The constraint is
     * asserted before that feature exists, which is when it is cheap to change.
     */
    @Test
    void rejectsADecisionWithoutAReviewerOrATime() {
        String studentId = aStudent();

        Throwable noDecisionDetails = catchThrowable(() ->
                insertSubmission(studentId, "a-valid-name", 1024L, "APPROVED", null, null));
        assertThat(noDecisionDetails).isInstanceOf(DataAccessException.class);
        assertThat(noDecisionDetails.getMessage()).contains("ck_submissions_decision");

        // And the same combination on a row that is still undecided.
        Throwable submittedButDecided = catchThrowable(() -> insertSubmission(
                studentId, "another-valid-name", 1024L, "SUBMITTED", null, studentId));
        assertThat(submittedButDecided).isInstanceOf(DataAccessException.class);
        assertThat(submittedButDecided.getMessage()).contains("ck_submissions_decision");
    }

    /**
     * The stored name is the only link between a row and its bytes, so two rows may not share one.
     *
     * <p>If they could, the second upload would overwrite the first student's artefact while both
     * rows still claimed to describe it — the quietest possible form of data loss.
     */
    @Test
    void rejectsTwoSubmissionsPointingAtTheSameStoredFile() {
        String studentId = aStudent();
        String storedFileName = "shared-stored-name";

        insertSubmission(studentId, storedFileName, 1024L, "SUBMITTED", null, null);

        Throwable thrown = catchThrowable(() ->
                insertSubmission(aStudent(), storedFileName, 1024L, "SUBMITTED", null, null));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("uq_submissions_stored_file_name");
    }

    /** A submission with no owner is not a state this application can produce or should read. */
    @Test
    void rejectsASubmissionOwnedByNobody() {
        Throwable thrown = catchThrowable(() ->
                insertSubmission(UUID.randomUUID().toString(), 1024L));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("fk_submissions_student_id");
    }

    // ------------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------------

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count == 1;
    }

    /** Creates a user and returns its identifier, for the foreign key to point at. */
    private String aStudent() {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(INSERT_USER, id, id + "@example.com", "$2a$10$notarealhash", "STUDENT");
        return id;
    }

    private void insertSubmission(String studentId, long fileSizeBytes) {
        insertSubmission(studentId, UUID.randomUUID().toString(), fileSizeBytes, "SUBMITTED",
                null, null);
    }

    /**
     * @param reviewedAt the decision timestamp, or {@code null} while the row is undecided
     * @param reviewerId the deciding user, or {@code null} while the row is undecided
     */
    private void insertSubmission(String studentId, String storedFileName, long fileSizeBytes,
                                  String status, Instant reviewedAt, String reviewerId) {
        jdbcTemplate.update(INSERT_SUBMISSION,
                UUID.randomUUID().toString(),
                studentId,
                storedFileName,
                fileSizeBytes,
                "0".repeat(64),
                status,
                reviewedAt == null ? null : Timestamp.from(reviewedAt),
                reviewerId);
    }

    private static long oversizedSize() {
        return 10L * 1024 * 1024 + 1;
    }
}
