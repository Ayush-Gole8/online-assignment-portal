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

    /**
     * Inserts a status-history row directly, for the same reason {@link #INSERT_SUBMISSION} does:
     * {@code SubmissionStatusHistory} has no setter and its factories only ever produce the two
     * shapes the service writes, so the database's own refusals can only be reached by handing it
     * something the entity cannot express.
     */
    private static final String INSERT_HISTORY = """
            INSERT INTO submission_status_history
                (id, submission_id, from_status, to_status, actor_id, note, created_at)
            VALUES (?, ?, ?, ?, ?, NULL, UTC_TIMESTAMP(6))
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

        // The decision columns are populated and consistent with a non-SUBMITTED row, so
        // ck_submissions_decision is satisfied and ck_submissions_status is the only constraint
        // this probe can violate. Leaving them null would break both, and MySQL reports whichever
        // it happens to evaluate first — which is how this test once failed naming
        // ck_submissions_decision and looked like a different bug.
        Throwable thrown = catchThrowable(() -> insertSubmission(
                studentId, "a-valid-name", 1024L, "PENDING", Instant.now(), aStudent()));

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
    // The status-history schema (V3)
    // ------------------------------------------------------------------------

    @Test
    void appliesTheStatusHistoryMigration() {
        assertThat(tableExists("submission_status_history")).isTrue();
    }

    /**
     * Both status columns admit only the three lifecycle states — the same vocabulary
     * {@code ck_submissions_status} enforces on the submission row.
     *
     * <p>An out-of-vocabulary value could only arrive from a bug: the entity writes the enum's name.
     * The constraint is the backstop that turns such a bug into a refused insert rather than a row
     * whose status no code can interpret.
     */
    @Test
    void rejectsAHistoryStatusOutsideTheEnumVocabulary() {
        String submissionId = aSubmission(aStudent());

        Throwable badTarget = catchThrowable(() ->
                insertHistory(submissionId, "SUBMITTED", "PENDING", aStudent()));
        assertThat(badTarget).isInstanceOf(DataAccessException.class);
        assertThat(badTarget.getMessage()).contains("ck_submission_status_history_to_status");

        Throwable badSource = catchThrowable(() ->
                insertHistory(submissionId, "PENDING", "APPROVED", aStudent()));
        assertThat(badSource).isInstanceOf(DataAccessException.class);
        assertThat(badSource.getMessage()).contains("ck_submission_status_history_from_status");
    }

    /**
     * The creation row is the only one whose {@code from_status} may be null, and a null there is
     * meaningful rather than missing: it marks the start of the lifecycle.
     */
    @Test
    void acceptsANullFromStatus_forTheCreationRow() {
        String submissionId = aSubmission(aStudent());

        insertHistory(submissionId, null, "SUBMITTED", aStudent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_status_history WHERE submission_id = ?",
                Integer.class, submissionId))
                .isEqualTo(1);
    }

    /** A history row with no actor is an untraceable transition — the thing the table exists to prevent. */
    @Test
    void rejectsAHistoryRowWithNoActor() {
        String submissionId = aSubmission(aStudent());

        Throwable thrown = catchThrowable(() ->
                insertHistory(submissionId, "SUBMITTED", "APPROVED", null));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
    }

    /** A history row whose actor is not a real user is refused by the foreign key. */
    @Test
    void rejectsAHistoryRowForAnActorThatDoesNotExist() {
        String submissionId = aSubmission(aStudent());

        Throwable thrown = catchThrowable(() -> insertHistory(
                submissionId, "SUBMITTED", "APPROVED", UUID.randomUUID().toString()));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("fk_submission_status_history_actor_id");
    }

    /** History describes a submission, so it cannot point at one that does not exist. */
    @Test
    void rejectsAHistoryRowForASubmissionThatDoesNotExist() {
        Throwable thrown = catchThrowable(() -> insertHistory(
                UUID.randomUUID().toString(), null, "SUBMITTED", aStudent()));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown.getMessage()).contains("fk_submission_status_history_submission_id");
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

    /** Creates an undecided submission owned by {@code studentId} and returns its identifier. */
    private String aSubmission(String studentId) {
        String submissionId = UUID.randomUUID().toString();
        jdbcTemplate.update(INSERT_SUBMISSION,
                submissionId,
                studentId,
                UUID.randomUUID().toString(),
                1024L,
                "0".repeat(64),
                "SUBMITTED",
                null,
                null);
        return submissionId;
    }

    /**
     * @param fromStatus the previous status, or {@code null} for a creation row
     * @param actorId    the acting user, or {@code null} to prove the column refuses it
     */
    private void insertHistory(String submissionId, String fromStatus, String toStatus,
                               String actorId) {
        jdbcTemplate.update(INSERT_HISTORY,
                UUID.randomUUID().toString(),
                submissionId,
                fromStatus,
                toStatus,
                actorId);
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
