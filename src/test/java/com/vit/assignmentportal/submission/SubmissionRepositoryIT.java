package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.DatabaseUserDetailsService;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.support.SubmissionTestData;
import com.vit.assignmentportal.support.TestCategories;
import com.vit.assignmentportal.support.TestUserFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The submission repository against a real MySQL, on the queries the application actually makes.
 *
 * <p>The rest of the integration suite drives this table through the web layer or a service, which
 * proves that a feature works but not that the query behind it is the one the feature intends. Here
 * each finder is called directly, so the things a service test cannot see cheaply become explicit:
 * that the owner really is part of the {@code WHERE} clause rather than a check in Java, that the
 * student's list is newest-first and the queue is oldest-first, and that paging bounds a set the
 * owner already scoped rather than widening it.
 *
 * <p>Testcontainers MySQL rather than H2 for the reason the base class gives — the ordering, the
 * {@code COLLATE} behaviour, and the entity-to-schema mapping are the engine's, and a different
 * engine would prove them for a database that never runs in production (ADR-003).
 *
 * <p>Every test uses its own account slugs, so the rows one test writes are never the rows another
 * asserts on. The suite shares one container, and isolation here is by distinct data rather than by
 * a rolled-back transaction — which also means the queries are exercised against a table that keeps
 * growing, as a real one does.
 */
@SpringBootTest
@Tag(TestCategories.INTEGRATION)
class SubmissionRepositoryIT extends MySqlIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant THIRD = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-10-02T09:00:00Z");

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DatabaseUserDetailsService userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestUserFactory users;

    @BeforeEach
    void buildUserFactory() {
        users = new TestUserFactory(userService, userDetailsService);
    }

    /**
     * A new row is given an identifier before the insert and read back by it through the mapping.
     *
     * <p>The round trip is the point. A row that saves and cannot be found would mean the identifier
     * Hibernate wrote is not the one it looks up by — which is exactly the class of defect the
     * {@code UUID}/{@code VARCHAR} mapping is exposed to on Hibernate 7.
     */
    @Test
    void persistsTheRowAndReadsItBackByItsIdentifier() {
        UUID studentId = users.idFor("repo-roundtrip", Role.STUDENT);
        Submission saved = submissionRepository.save(SubmissionTestData.draft(studentId, FIRST));

        assertThat(saved.getId()).as("the identifier is assigned before the insert").isNotNull();
        assertThat(saved.getVersion()).as("a freshly inserted row is version zero").isZero();

        Submission reloaded = submissionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getStudentId()).isEqualTo(studentId);
        assertThat(reloaded.getTitle()).isEqualTo(SubmissionTestData.DEFAULT_TITLE);
        assertThat(reloaded.getCourseCode()).isEqualTo(SubmissionTestData.DEFAULT_COURSE_CODE);
        assertThat(reloaded.getChecksumSha256())
                .as("the CHAR(64) digest round-trips unchanged")
                .isEqualTo(SubmissionTestData.sha256Hex(SubmissionTestData.PDF_BYTES));
        assertThat(reloaded.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(reloaded.getCreatedAt()).isEqualTo(FIRST);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(FIRST);
        assertThat(reloaded.getReviewedAt()).isNull();
        assertThat(reloaded.getReviewerId()).isNull();
    }

    /**
     * The status is stored as its readable name, not as an ordinal.
     *
     * <p>Read straight from the column rather than through the entity, because that is the claim:
     * {@code @Enumerated(STRING)} writes {@code "SUBMITTED"}, so a schema dump reads and a reordered
     * enum cannot silently reinterpret old rows (docs/architecture/data-model.md §3).
     */
    @Test
    void storesTheStatusAsItsReadableName() {
        UUID studentId = users.idFor("repo-status-name", Role.STUDENT);
        Submission saved = submissionRepository.save(SubmissionTestData.draft(studentId, FIRST));

        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM submissions WHERE id = ?", String.class,
                saved.getId().toString());

        assertThat(storedStatus).isEqualTo("SUBMITTED");
    }

    /**
     * A student's list is only their own rows, newest first, and paging bounds that already-scoped
     * set without widening it.
     */
    @Test
    void returnsOnlyTheOwnersRowsNewestFirst() {
        UUID owner = users.idFor("repo-list-owner", Role.STUDENT);
        UUID stranger = users.idFor("repo-list-stranger", Role.STUDENT);

        Submission older = submissionRepository.save(SubmissionTestData.draft(owner, FIRST));
        Submission newer = submissionRepository.save(SubmissionTestData.draft(owner, SECOND));
        Submission anotherStudents = submissionRepository.save(SubmissionTestData.draft(stranger, THIRD));

        Page<Submission> fullPage = submissionRepository
                .findByStudentIdOrderByCreatedAtDesc(owner, PageRequest.of(0, 10));

        assertThat(idsOf(fullPage))
                .as("newest first, and nobody else's work")
                .containsExactly(newer.getId(), older.getId());
        assertThat(idsOf(fullPage)).doesNotContain(anotherStudents.getId());

        Page<Submission> firstItemOnly = submissionRepository
                .findByStudentIdOrderByCreatedAtDesc(owner, PageRequest.of(0, 1));

        assertThat(idsOf(firstItemOnly)).containsExactly(newer.getId());
        assertThat(firstItemOnly.getTotalElements())
                .as("the page is a window on the whole list, not a smaller list")
                .isEqualTo(2L);
    }

    /**
     * The owner-scoped lookup returns a row only to its owner.
     *
     * <p>This is the query that makes "somebody else's submission is never loaded" true rather than
     * aspirational: a stranger asking by the exact identifier gets an empty result, not a row refused
     * afterwards.
     */
    @Test
    void returnsARowOnlyToItsOwner() {
        UUID owner = users.idFor("repo-owner-lookup", Role.STUDENT);
        UUID stranger = users.idFor("repo-stranger-lookup", Role.STUDENT);
        Submission saved = submissionRepository.save(SubmissionTestData.draft(owner, FIRST));

        assertThat(submissionRepository.findByIdAndStudentId(saved.getId(), owner)).isPresent();
        assertThat(submissionRepository.findByIdAndStudentId(saved.getId(), stranger)).isEmpty();
    }

    /**
     * The queue is one status, oldest first, and a decided row leaves it.
     *
     * <p>Asserted against a table that other tests also write to, so the ordering claim is that the
     * returned timestamps are non-decreasing rather than that a particular list appears — which is
     * the property the query is responsible for either way.
     */
    @Test
    void returnsOnlyAwaitingWorkOldestFirst() {
        UUID studentId = users.idFor("repo-queue-student", Role.STUDENT);
        UUID reviewerId = users.idFor("repo-queue-reviewer", Role.REVIEWER);

        Submission awaiting = submissionRepository.save(SubmissionTestData.draft(studentId, FIRST));
        Submission toDecide = submissionRepository.save(SubmissionTestData.draft(studentId, SECOND));
        Submission decided = decide(toDecide, reviewerId, SubmissionStatus.APPROVED);

        Page<Submission> queue = submissionRepository.findByStatusOrderByCreatedAtAsc(
                SubmissionStatus.SUBMITTED, PageRequest.of(0, 50));

        assertThat(idsOf(queue)).contains(awaiting.getId());
        assertThat(idsOf(queue))
                .as("a decided submission is no longer waiting")
                .doesNotContain(decided.getId());
        assertThat(createdAtOf(queue))
                .as("the queue is worked oldest first")
                .isSorted();
    }

    /**
     * The three counts are per status and per student, and they are a {@code COUNT} rather than a
     * scan — the dashboard shows three numbers and no rows.
     */
    @Test
    void countsEachStatusSeparatelyForOneStudent() {
        UUID studentId = users.idFor("repo-counts-student", Role.STUDENT);
        UUID reviewerId = users.idFor("repo-counts-reviewer", Role.REVIEWER);

        submissionRepository.save(SubmissionTestData.draft(studentId, FIRST));
        decide(submissionRepository.save(SubmissionTestData.draft(studentId, SECOND)), reviewerId,
                SubmissionStatus.APPROVED);
        decide(submissionRepository.save(SubmissionTestData.draft(studentId, THIRD)), reviewerId,
                SubmissionStatus.REJECTED);

        assertThat(submissionRepository.countByStudentIdAndStatus(studentId,
                SubmissionStatus.SUBMITTED)).isEqualTo(1L);
        assertThat(submissionRepository.countByStudentIdAndStatus(studentId,
                SubmissionStatus.APPROVED)).isEqualTo(1L);
        assertThat(submissionRepository.countByStudentIdAndStatus(studentId,
                SubmissionStatus.REJECTED)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------------

    private static List<UUID> idsOf(Page<Submission> page) {
        List<UUID> ids = new ArrayList<>();
        for (Submission submission : page.getContent()) {
            ids.add(submission.getId());
        }
        return ids;
    }

    private static List<Instant> createdAtOf(Page<Submission> page) {
        List<Instant> times = new ArrayList<>();
        for (Submission submission : page.getContent()) {
            times.add(submission.getCreatedAt());
        }
        return times;
    }

    /**
     * Re-reads a row, applies a terminal decision to it, and saves it — the same two steps a
     * reviewer's decision takes, without going through the reviewer's feature.
     *
     * <p>The re-read matters: it drives the update through a freshly loaded entity with its real
     * version, rather than mutating the detached copy {@code save} returned.
     */
    private Submission decide(Submission submission, UUID reviewerId, SubmissionStatus outcome) {
        Submission fresh = submissionRepository.findById(submission.getId()).orElseThrow();
        if (outcome == SubmissionStatus.APPROVED) {
            fresh.approve(reviewerId, DECIDED_AT);
        } else {
            fresh.reject(reviewerId, "Not a readable PDF.", DECIDED_AT);
        }
        return submissionRepository.save(fresh);
    }
}
