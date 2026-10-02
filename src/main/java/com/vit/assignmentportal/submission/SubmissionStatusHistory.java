package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.common.UuidToVarcharConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of a submission's status history: a single, immutable record that the status went from
 * one value to another, who caused it, and when (docs/architecture/data-model.md §4, FR-21).
 *
 * <p>Maps the {@code submission_status_history} table created by
 * {@code V3__submission_status_history.sql}. As with every other entity, Hibernate validates this
 * mapping against the migration at startup and never creates or alters schema itself (ADR-003).
 *
 * <h2>Append-only</h2>
 *
 * <p>Rows are written and never updated or deleted — there is no setter and no {@code @PreUpdate}.
 * That is what makes the table trustworthy as the audit of record for the workflow: a correction is
 * a new compensating row, not an edit of an old one. The lifecycle of a submission is readable from
 * this table alone, oldest first.
 *
 * <h2>Why it lives in {@code submission}, not {@code review}</h2>
 *
 * <p>The decision that produces a transition belongs to the reviewer, but the <em>data</em> is
 * about a submission, and it is written in the same transaction as the change to the
 * {@link Submission} row. {@code review} may not import a submission repository or mutate a
 * submission (docs/architecture/application-structure.md §4 rule 1), so the write has to happen
 * where the submission's own persistence lives — here, driven by {@code SubmissionService}.
 */
@Entity
@Table(name = "submission_status_history")
public class SubmissionStatusHistory {

    /**
     * Application-assigned, like every other identifier in this schema.
     *
     * <p>A {@code UUID} rather than an auto-increment integer so that the table follows the same
     * identity model as {@code users} and {@code submissions} — the application sets the id before
     * the insert, and nothing in the schema relies on a database-generated key.
     *
     * <p>{@link JdbcTypeCode} rather than {@link UuidToVarcharConverter}: Hibernate 7 rejects an
     * {@code AttributeConverter} on an {@code @Id} attribute, so the identifier pins its column type
     * with the JDBC type code while the non-identifier {@code UUID} columns below use the converter.
     */
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    /**
     * The submission this transition belongs to.
     *
     * <p>A bare identifier for the same reason {@code Submission.studentId} is one: the foreign key
     * guarantees it names a real submission, and nothing here needs to traverse to the row itself.
     */
    @Convert(converter = UuidToVarcharConverter.class)
    @Column(name = "submission_id", nullable = false, updatable = false, length = 36)
    private UUID submissionId;

    /**
     * The status before the transition, or {@code null} for the creation row.
     *
     * <p>The first row of every submission's history is its creation: {@code from = null},
     * {@code to = SUBMITTED}. A null here is therefore meaningful rather than missing — it marks
     * the beginning of the lifecycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private SubmissionStatus fromStatus;

    /** The status after the transition. Never null — a transition always arrives somewhere. */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16)
    private SubmissionStatus toStatus;

    /**
     * Who caused the transition: the student for the creation row, the reviewer for a decision.
     *
     * <p>Stored for the audit trail; it is deliberately <em>not</em> carried into the read model
     * shown to a student ({@link SubmissionHistoryEntry}), which would be disclosing who reviewed
     * their work for no benefit the page needs.
     */
    @Convert(converter = UuidToVarcharConverter.class)
    @Column(name = "actor_id", nullable = false, updatable = false, length = 36)
    private UUID actorId;

    /**
     * The reviewer's comment, required by the service for a rejection (FR-19) and absent otherwise.
     *
     * <p>Nullable at the database level so the creation row and an approval can exist without one;
     * the rule that a rejection must carry a reason lives in the service, where it can produce a
     * message, not in the column.
     */
    @Column(name = "note", length = 2000)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. Not part of the application's API — use the factories. */
    protected SubmissionStatusHistory() {
    }

    private SubmissionStatusHistory(UUID submissionId, SubmissionStatus fromStatus,
                                    SubmissionStatus toStatus, UUID actorId, String note) {
        this.submissionId = submissionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.note = note;
    }

    /**
     * The first entry in a submission's history: its creation, in {@code SUBMITTED}.
     *
     * <p>{@code fromStatus} is null because nothing preceded it, and there is no note — the student
     * did not decide anything, they submitted. Written in the same transaction as the submission
     * row (FR-15).
     *
     * @param submissionId the submission that was created
     * @param actorId      the student who submitted it
     */
    public static SubmissionStatusHistory creation(UUID submissionId, UUID actorId) {
        return new SubmissionStatusHistory(submissionId, null, SubmissionStatus.SUBMITTED, actorId,
                null);
    }

    /**
     * A reviewer's decision: the move out of {@code SUBMITTED} into a terminal status.
     *
     * @param submissionId the submission being decided
     * @param actorId      the reviewer who decided
     * @param fromStatus   the status before the decision — {@code SUBMITTED} in the MVP
     * @param toStatus     the status after — {@code APPROVED} or {@code REJECTED}
     * @param note         the reviewer's comment: required for a rejection, {@code null} otherwise
     */
    public static SubmissionStatusHistory transition(UUID submissionId, UUID actorId,
                                                     SubmissionStatus fromStatus,
                                                     SubmissionStatus toStatus, String note) {
        return new SubmissionStatusHistory(submissionId, fromStatus, toStatus, actorId, note);
    }

    /** Assigns the identifier and the timestamp on the way into the database. */
    @PrePersist
    void onInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public SubmissionStatus getFromStatus() {
        return fromStatus;
    }

    public SubmissionStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Identity is the identifier, with the same transient-instance handling as {@link Submission}. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubmissionStatusHistory entry) || id == null) {
            return false;
        }
        return id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Omits the note: it can be a rejection reason, and a log line should identify, not quote. */
    @Override
    public String toString() {
        return "SubmissionStatusHistory[id=%s, submissionId=%s, %s->%s]"
                .formatted(id, submissionId, fromStatus, toStatus);
    }
}
