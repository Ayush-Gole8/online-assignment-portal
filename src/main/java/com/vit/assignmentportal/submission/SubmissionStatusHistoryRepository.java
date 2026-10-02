package com.vit.assignmentportal.submission;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SubmissionStatusHistory}.
 *
 * <p>The table is append-only, so this interface deliberately offers only a write (inherited
 * {@code save}) and one read. There is no update or delete method, and none is inherited into use —
 * a history whose rows can be edited is not a history.
 *
 * <p>As with {@link SubmissionRepository}, {@code SubmissionService} is the only intended caller.
 * The {@code review} feature reaches this data through that service, never through this interface
 * directly (docs/architecture/application-structure.md §4 rule 1).
 */
public interface SubmissionStatusHistoryRepository
        extends JpaRepository<SubmissionStatusHistory, UUID> {

    /**
     * Every history row for one submission, oldest first.
     *
     * <p>Ascending by {@code created_at} because history reads as a timeline: the creation entry
     * first, the decision last. The index on {@code (submission_id, created_at)} serves both the
     * filter and the sort.
     */
    List<SubmissionStatusHistory> findBySubmissionIdOrderByCreatedAtAsc(UUID submissionId);
}
