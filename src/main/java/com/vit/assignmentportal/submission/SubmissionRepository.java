package com.vit.assignmentportal.submission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Submission}.
 *
 * <p>Note which methods are <em>not</em> here. There is no {@code findAll}, and no
 * {@code findById} inherited into use, because both would let a caller load a submission without
 * saying whose it is. Every read this feature performs is expressed as a query that carries the
 * owner, so a student's isolation is a property of the interface rather than a check somebody has
 * to remember to write at each call site.
 *
 * <p>{@code JpaRepository} still contributes {@code findById} and {@code findAll}. They are not
 * hidden — that would mean not extending it — but nothing in this feature calls them, and
 * {@code SubmissionService} is the only intended entry point.
 */
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    /**
     * Every submission belonging to one student, newest first.
     *
     * <p>Ordered by {@code created_at} descending because the student's list answers "what have I
     * handed in recently?", and the index on {@code (student_id, created_at)} serves both the
     * filter and the sort without a filesort.
     */
    List<Submission> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

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
}
