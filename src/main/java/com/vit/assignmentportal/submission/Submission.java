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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A student's coursework artefact: the metadata row that describes a file on the storage volume
 * (docs/architecture/data-model.md §3).
 *
 * <p>Maps the {@code submissions} table created by {@code V2__submissions.sql}. Hibernate
 * validates this mapping against that migration at startup and never creates or alters schema
 * itself (ADR-003).
 *
 * <p><strong>Contains metadata only.</strong> The bytes live on the volume, addressed by
 * {@link #storedFileName} (ADR-004). A row and a file are two halves of one artefact, and the
 * only place that keeps them in step is {@code SubmissionService}.
 *
 * <p><strong>This entity does not leave the {@code submission} feature.</strong> Controllers
 * hand {@link SubmissionSummary} or {@link SubmissionDetail} records to templates; the entity is
 * never bound to a view, because a template that can reach a field can render it.
 */
@Entity
@Table(name = "submissions")
public class Submission {

    /**
     * Assigned by the application, not by the database.
     *
     * <p>A UUID rather than an auto-increment integer because a submission's identifier appears
     * in URLs. A sequential integer there announces how many submissions exist and invites
     * anyone to walk the range — and, because the ownership check answers 404 rather than 403,
     * an enumeration attempt would otherwise reveal exactly which identifiers are real.
     *
     * <p>{@link JdbcTypeCode} pins the column type, so it stays the migration's decision rather
     * than the ORM's. Hibernate 7 refuses an {@code AttributeConverter} on an identifier
     * attribute — {@code AnnotationException: 'AttributeConverter' not allowed for attribute 'id'
     * annotated '@jakarta.persistence.Id'} — which is why the identifier states the JDBC type
     * directly while the non-identifier {@code UUID} columns below use the converter. See
     * {@link UuidToVarcharConverter} for why {@code ddl-auto: validate} makes pinning this
     * worthwhile at all.
     */
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    /**
     * The owner.
     *
     * <p>A plain identifier, not a {@code @ManyToOne} association to {@code auth.User}. The
     * submission feature has no business loading an entity another feature owns, and the
     * ownership check is a {@code WHERE student_id = ?} clause — for which an identifier is
     * precisely what is needed. An association would also make it possible to traverse from a
     * submission to a user's password hash, which nothing here should be able to do.
     */
    @Convert(converter = UuidToVarcharConverter.class)
    @Column(name = "student_id", nullable = false, updatable = false, length = 36)
    private UUID studentId;

    /** The student's own title for the work. Free text, bounded by the column width. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The course the work belongs to, as the student entered it. */
    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    /**
     * The name the file had on the student's machine.
     *
     * <p><strong>Display metadata only.</strong> It is never interpolated into a path — that is
     * the entire purpose of {@link #storedFileName} ({@code CLAUDE.md} §7 rule 5). It is
     * normalised on the way in by {@code FileValidationService.safeDisplayName} so that it is
     * safe to store, log, and show.
     */
    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    /**
     * The server-generated name the bytes are stored under.
     *
     * <p>A bare UUID, produced by {@code StorageIdentifier}. Immutable: rewriting it would
     * orphan the file it names, so there is no setter and the column is not updatable.
     */
    @Column(name = "stored_file_name", nullable = false, updatable = false, length = 36)
    private String storedFileName;

    /**
     * The media type recorded at upload time.
     *
     * <p>Always {@code application/pdf} in the MVP, and stored anyway: lifting the single-format
     * restriction should be a change to validation, not a migration. Note that this is the
     * <em>validated</em> type, not the string the client claimed.
     */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** The size of the stored artefact in bytes. Constrained to the 10 MB limit by the schema. */
    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /**
     * The SHA-256 digest of the stored bytes, as 64 lowercase hexadecimal characters.
     *
     * <p>Computed from the same array that was written, so it describes the file on the volume
     * rather than a file that was nearly written.
     *
     * <p>{@code columnDefinition} pins the physical type for the same reason
     * {@link UuidToVarcharConverter} does: {@code V2__submissions.sql} declares this column
     * {@code CHAR(64)} — a hex digest has exactly one length — while a plain {@code String} maps
     * to {@code varchar(64)}. Under {@code ddl-auto: validate} that difference is a startup
     * failure, and Hibernate generates no DDL here, so stating the type the migration actually
     * created is what keeps the two in agreement.
     */
    @Column(name = "checksum_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String checksumSha256;

    /**
     * Stored as a string, not an ordinal. A readable value survives a schema dump and cannot
     * silently change meaning if the enum is reordered (docs/architecture/data-model.md §3).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubmissionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When a reviewer decided. {@code null} until then.
     *
     * <p>Denormalised from the status history, which is the record of truth for the workflow;
     * this copy exists because the reviewer's queue needs it on every row, and joining an
     * append-only log to render a list is wasteful (docs/architecture/data-model.md §3).
     *
     * <p>Unused in this milestone — nothing sets it. The review feature owns that transition.
     */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * The reviewer who decided. {@code null} until then, and unused in this milestone.
     *
     * <p>Kept as a bare identifier for the same reason as {@link #studentId}, and because the
     * database foreign key already guarantees it names a real user.
     */
    @Convert(converter = UuidToVarcharConverter.class)
    @Column(name = "reviewer_id", length = 36)
    private UUID reviewerId;

    /**
     * Why a submission was rejected. {@code null} unless the status is
     * {@link SubmissionStatus#REJECTED}, and unused in this milestone.
     */
    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    /**
     * JPA's optimistic lock (docs/architecture/data-model.md §3).
     *
     * <p>Hibernate increments this on every update and adds it to the {@code WHERE} clause, so a
     * second writer working from a stale copy updates nothing and is told so, instead of quietly
     * overwriting the first. It matters the moment two reviewers open the same submission — and
     * the status transition is exactly the update that must not be applied twice.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA. Not part of the application's API — use {@link #upload}. */
    protected Submission() {
    }

    private Submission(UUID studentId, String title, String courseCode, String originalFileName,
                       String storedFileName, String contentType, long fileSizeBytes,
                       String checksumSha256) {
        this.studentId = studentId;
        this.title = title;
        this.courseCode = courseCode;
        this.originalFileName = originalFileName;
        this.storedFileName = storedFileName;
        this.contentType = contentType;
        this.fileSizeBytes = fileSizeBytes;
        this.checksumSha256 = checksumSha256;
        // The only status a submission can be created in. There is deliberately no method that
        // moves it anywhere else — the review feature will own that transition, and until it
        // exists the only reachable state is SUBMITTED.
        this.status = SubmissionStatus.SUBMITTED;
    }

    /**
     * Creates a submission row describing a file that has already been stored.
     *
     * <p>The argument order is the point of this factory: every field here is something that was
     * <em>established</em> before the row existed — the bytes are on the volume, the checksum was
     * computed from them, the size measured. Nothing is derived, and nothing is guessed.
     *
     * @param studentId        the owner, taken from the authenticated principal — never from the
     *                         request ({@code CLAUDE.md} §7 rule 8)
     * @param title            the student's title for the work, already trimmed and length-checked
     * @param courseCode       the course code, already trimmed and length-checked
     * @param originalFileName the display name, already normalised by
     *                         {@code FileValidationService.safeDisplayName}
     * @param storedFileName   the identifier the bytes were written under
     * @param contentType      the validated media type
     * @param fileSizeBytes    the size of the stored artefact
     * @param checksumSha256   the digest of the stored bytes
     */
    public static Submission upload(UUID studentId, String title, String courseCode,
                                    String originalFileName, String storedFileName,
                                    String contentType, long fileSizeBytes,
                                    String checksumSha256) {
        return new Submission(studentId, title, courseCode, originalFileName, storedFileName,
                contentType, fileSizeBytes, checksumSha256);
    }

    /**
     * Assigns the identifier and the timestamps on the way into the database.
     *
     * <p>Doing it here rather than in the constructor keeps the entity's id {@code null} until it
     * is persisted, which is what lets Spring Data recognise the instance as new and issue an
     * {@code INSERT} rather than guessing at a {@code MERGE}.
     */
    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public String getTitle() {
        return title;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Identity is the identifier. Two {@code Submission} instances are the same submission when
     * they hold the same id, regardless of whether either has been mutated.
     *
     * <p>Transient instances fall back to reference equality rather than comparing a pair of
     * nulls — otherwise every unsaved submission would equal every other unsaved submission,
     * which turns a {@code Set} of pending rows into a single element.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Submission submission) || id == null) {
            return false;
        }
        return id.equals(submission.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Renders the identifying and descriptive fields only.
     *
     * <p>There is no credential here to leak, unlike {@code auth.User}, but the omission of the
     * file name and checksum is still deliberate: this object reaches log lines, and a log line
     * should say which submission was involved, not reproduce its contents.
     */
    @Override
    public String toString() {
        return "Submission[id=%s, studentId=%s, status=%s]".formatted(id, studentId, status);
    }
}
