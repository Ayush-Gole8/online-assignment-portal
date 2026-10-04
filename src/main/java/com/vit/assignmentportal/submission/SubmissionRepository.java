package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Submission}.
 *
 * <p>Note how the reads divide. Every query a <strong>student</strong> makes carries the owner, so
 * their isolation is a property of this interface rather than a check somebody has to remember to
 * write at each call site. The <strong>reviewer</strong>'s reads do not, because a reviewer is
 * authorised by role at the route and reviewing only one's own work would be meaningless.
 *
 * <p>There is deliberately no {@code findAll} in use. {@code JpaRepository} contributes it, and
 * {@code findById} — which the reviewer path does call, from inside {@code SubmissionService}'s
 * reviewer-facing reads, after the {@code /review/**} role rule has already admitted the request.
 * They are not hidden; that would mean not extending {@code JpaRepository}.
 * {@code SubmissionService} is the only intended entry point either way, and the lookups it exposes
 * return read models rather than entities, so a {@code Submission} cannot leave the feature.
 */
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    /**
     * One page of a student's submissions, newest first.
     *
     * <p>Ordered by {@code created_at} descending because the student's list answers "what have I
     * handed in recently?", and the index on {@code (student_id, created_at)} serves both the
     * filter and the sort without a filesort.
     *
     * <p>The owner is a parameter of the query and the page is a bound on it, so paging cannot widen
     * the result — page 2 of a student's own work is still only that student's work. The
     * {@code OrderBy} in the method name supplies the sort, so a caller passes an unsorted
     * {@link Pageable} and cannot accidentally re-order away from the indexed one.
     */
    Page<Submission> findByStudentIdOrderByCreatedAtDesc(UUID studentId, Pageable pageable);

    /**
     * One submission, but only if it belongs to the given student.
     *
     * <p>The ownership test is in the {@code WHERE} clause, not in Java. A
     * {@code findById} followed by an {@code if} in the service would work, and would also be one
     * refactoring away from somebody removing the {@code if} — or from a second caller being
     * written that forgets it. As written, a submission that belongs to somebody else is not
     * merely refused, it is never loaded.
     *
     * @return the submission, or empty when it does not exist or is not this student's
     */
    Optional<Submission> findByIdAndStudentId(UUID id, UUID studentId);

    /**
     * One page of one status, oldest first — the reviewer's queue (FR-16).
     *
     * <p>Ascending by {@code created_at}, unlike the student's list: a queue is worked through, so
     * the submission that has waited longest comes first. The index on {@code (status, created_at)}
     * answers both the filter and the sort.
     *
     * <p>Not scoped by any user, and that is deliberate — a reviewer is authorised by role at the
     * route, not by ownership. This is the one query in this interface that does not carry an
     * owner, and the comment on the type explains why the others all do. The status is fixed by the
     * caller ({@code SUBMITTED}), so paging cannot surface a decided submission as if it were
     * waiting.
     */
    Page<Submission> findByStatusOrderByCreatedAtAsc(SubmissionStatus status, Pageable pageable);

    /**
     * How many of one student's submissions are in one status.
     *
     * <p>A {@code COUNT} rather than loading the rows and sizing the list: the dashboard shows three
     * numbers and nothing else, so reading the rows to count them would fetch data the page never
     * renders. The count is still scoped by owner in the {@code WHERE} clause, so it cannot report
     * another student's work.
     */
    long countByStudentIdAndStatus(UUID studentId, SubmissionStatus status);
}
