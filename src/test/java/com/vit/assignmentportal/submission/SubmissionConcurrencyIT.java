package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.DatabaseUserDetailsService;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.common.SubmissionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two reviewers decide the same submission at the same moment. One decision must be recorded, the
 * other must be refused — and neither may be silently lost (FR-20).
 *
 * <h2>Why this lives in the {@code submission} package</h2>
 *
 * <p>The scenario is the reviewer's, but the mechanism is not. {@code @Version} is declared on the
 * {@link Submission} entity and the transitions are package-private methods on it, so the only way
 * to drive two genuine, overlapping transactions is from here, where the entity's own API is
 * reachable. Nothing below touches a reviewer-facing class; that is the point. The lock is the
 * reason the reviewer's policy class needs no second copy of the terminality rule.
 *
 * <h2>How a race is made deterministic</h2>
 *
 * <p>A real race cannot be scheduled from a test, but the <em>interleaving that matters</em> can be
 * forced with two latches and two threads:
 *
 * <ol>
 *   <li>Reviewer A begins a transaction and reads the submission — at version 0.</li>
 *   <li>A pauses, holding that stale copy.</li>
 *   <li>Reviewer B reads, decides, and <strong>commits</strong>. The row is now version 1.</li>
 *   <li>A resumes and applies its decision to the copy it still holds, then commits.</li>
 * </ol>
 *
 * <p>At step 4 Hibernate issues {@code UPDATE ... WHERE id = ? AND version = 0}. MySQL's
 * {@code REPEATABLE READ} means the plain reads in steps 1 and 3 take no locks, so the two threads
 * never block each other, while the {@code UPDATE} is a current read that sees version 1. No row
 * matches, Hibernate raises {@code StaleObjectStateException}, and Spring's JPA dialect translates
 * it into {@link ObjectOptimisticLockingFailureException} on the way out of the transaction.
 *
 * <p>The alternative — handing two detached copies to {@code save} — would depend on how Hibernate's
 * {@code merge} treats a stale version, which is subtle enough that a green test could mean the copy
 * was re-read rather than that the lock worked. Two real transactions make the staleness a fact
 * about the database rather than a property of the ORM's merge path.
 */
@SpringBootTest
class SubmissionConcurrencyIT extends MySqlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    /** Generous, because it is a failure marker rather than a performance assertion. */
    private static final long LATCH_TIMEOUT_SECONDS = 20;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DatabaseUserDetailsService userDetailsService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The application's own time source.
     *
     * <p>The decision instant is read from it once, below, rather than taken twice from
     * {@code Instant.now()}. The two threads must be applying <em>the same</em> decision time for this
     * to be a test about the optimistic lock: two different wall-clock reads would leave the
     * assertion on {@code reviewed_at} — or its absence — depending on which thread happened to be
     * scheduled first, which is the one thing a race test must not do.
     */
    @Autowired
    private Clock clock;

    /**
     * The loser's decision is refused, the winner's stands, no rejection reason is written, and the
     * audit trail is untouched.
     *
     * <p>All four are asserted in one test because they are one event. Asserting only the exception
     * would pass if the losing write had corrupted the row and then failed for some unrelated
     * reason; asserting only the final row would pass if both writes had somehow been applied. The
     * row, the version, and the history together say that exactly one decision happened.
     */
    @Test
    void refusesTheLoserOfTwoConcurrentDecisions() throws Exception {
        AuthenticatedUser student = userFor("concurrency-student", Role.STUDENT);
        AuthenticatedUser reviewerA = userFor("concurrency-reviewer-a", Role.REVIEWER);
        AuthenticatedUser reviewerB = userFor("concurrency-reviewer-b", Role.REVIEWER);

        Submission created = submissionService.submit(student, pdf(), "Concurrent work", "CS101");
        UUID submissionId = created.getId();

        // One decision time for both reviewers, read from the application's clock. See the field's
        // javadoc: a race test must not let the scheduler decide what the timestamps are.
        Instant decidedAt = clock.instant();

        CountDownLatch aHasReadAtVersionZero = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);

        ExecutorService reviewerAThread = Executors.newSingleThreadExecutor();
        try {
            // Step 1-2, and Step 4: reviewer A reads, waits until B has committed, then writes.
            // The lambda is block-bodied so it is unambiguously a Callable rather than a Runnable
            // (an expression-body lambda returning a value is compatible with both overloads).
            Future<?> staleReviewer = reviewerAThread.submit(() -> {
                return inTransaction(() -> {
                    Submission stale = submissionRepository.findById(submissionId).orElseThrow();

                    // Released before the premise is asserted: if the premise is ever wrong, the
                    // other thread must not be left waiting on a latch that will never open.
                    aHasReadAtVersionZero.countDown();
                    assertThat(stale.getVersion()).isZero();

                    await(bHasCommitted);

                    stale.approve(reviewerA.id(), decidedAt);
                    return null;
                });
            });

            // Step 3: reviewer B reads, decides, and commits — while A holds version 0.
            await(aHasReadAtVersionZero);
            inTransaction(() -> {
                Submission fresh = submissionRepository.findById(submissionId).orElseThrow();
                fresh.approve(reviewerB.id(), decidedAt);
                return null;
            });
            bHasCommitted.countDown();

            // Step 4 completes: A's write is refused rather than applied.
            assertThatThrownBy(() -> staleReviewer.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the stale decision must be refused, not silently applied")
                    .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            reviewerAThread.shutdownNow();
        }

        Submission afterTheRace = submissionRepository.findById(submissionId).orElseThrow();
        assertThat(afterTheRace.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(afterTheRace.getReviewerId())
                .as("the reviewer who committed first is the one on the row")
                .isEqualTo(reviewerB.id());
        assertThat(afterTheRace.getRejectionReason()).isNull();
        assertThat(afterTheRace.getVersion())
                .as("exactly one update was applied")
                .isEqualTo(1L);

        assertThat(historyRows(submissionId))
                .as("the losing write left no history entry behind")
                .hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------------

    /** Runs {@code work} inside its own transaction, committing on return and rolling back on throw. */
    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** Waits for the other thread's signal, failing the test rather than hanging forever. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for the other reviewer's transaction.");
            }
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating the race", cause);
        }
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "essay.pdf", "application/pdf", PDF_BYTES);
    }

    /** A real principal for a real account, built through the same service Spring Security uses. */
    private AuthenticatedUser userFor(String slug, Role role) {
        String email = slug + "@example.com";
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, role);
        }
        return (AuthenticatedUser) userDetailsService.loadUserByUsername(email);
    }

    private List<Map<String, Object>> historyRows(UUID submissionId) {
        return jdbcTemplate.queryForList("""
                SELECT from_status, to_status, actor_id, note
                  FROM submission_status_history
                 WHERE submission_id = ?
                """, submissionId.toString());
    }
}
