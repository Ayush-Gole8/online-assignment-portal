package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.storage.ChecksumService;
import com.vit.assignmentportal.storage.FileValidationService;
import com.vit.assignmentportal.storage.StorageIdentifier;
import com.vit.assignmentportal.storage.StorageService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Everything that happens to a submission: accepting an upload, listing a student's own work, and
 * opening one of them.
 *
 * <h2>The two-phase write, and why this class exists</h2>
 *
 * <p>Accepting an upload writes to two places that cannot be committed together: the bytes go to
 * a file on a volume, and the metadata goes to a row in MySQL. There is no distributed
 * transaction between them and this application does not pretend otherwise. What it does instead
 * is order the writes so that every failure leaves a state an operator can reason about:
 *
 * <ol>
 *   <li><strong>Nothing is written until everything is validated.</strong> A refused upload
 *       stores no file and creates no row, so there is nothing to clean up. This is why the
 *       validation call sits above the storage call rather than beside the persistence call.</li>
 *   <li><strong>The file is written before the row.</strong> The row records a checksum and a
 *       size that were <em>measured from the bytes</em>, so the bytes have to exist first. The
 *       reverse order would mean writing a row that claims a file exists before it does — and a
 *       crash in between leaves a row pointing at nothing, which every later read has to defend
 *       against.</li>
 *   <li><strong>If the row is not committed, the file is removed.</strong> This is the case the
 *       ordering above creates, so it is the case this class is responsible for closing. See
 *       {@link #registerFileDiscardOnRollback(StorageIdentifier)}.</li>
 * </ol>
 *
 * <p>The failure that remains unhandled is worth stating plainly: if the process dies between the
 * write and the commit, no code runs and the file is orphaned. Nothing in this design can prevent
 * that — it needs a sweeper that reconciles the volume against the table, which is not in the MVP.
 * The artefact is not lost and no row lies; it is simply untidy, and the file name identifies it.
 * See docs/architecture/security.md and docs/architecture/data-model.md §6.
 *
 * <h2>The dependency on {@code auth}</h2>
 *
 * <p>This class takes an {@link AuthenticatedUser} and so imports from {@code auth}. That edge is
 * the sanctioned one: features obtain the current principal from {@code auth} and never reach
 * into another feature's entities or repositories (docs/architecture/application-structure.md §4).
 * The distinct thing it does <em>not</em> do is import {@code auth.User} — an {@code
 * AuthenticatedUser} carries an id, an email, a role, and no credential material.
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    /** The width of the {@code title} column. */
    private static final int MAX_TITLE_LENGTH = 200;

    /** The width of the {@code course_code} column. */
    private static final int MAX_COURSE_CODE_LENGTH = 20;

    private final SubmissionRepository submissionRepository;
    private final SubmissionStatusHistoryRepository statusHistoryRepository;
    private final StorageService storageService;
    private final FileValidationService fileValidationService;
    private final ChecksumService checksumService;
    private final Clock clock;

    public SubmissionService(SubmissionRepository submissionRepository,
                             SubmissionStatusHistoryRepository statusHistoryRepository,
                             StorageService storageService,
                             FileValidationService fileValidationService,
                             ChecksumService checksumService,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.storageService = storageService;
        this.fileValidationService = fileValidationService;
        this.checksumService = checksumService;
        this.clock = clock;
    }

    /**
     * The application's current instant.
     *
     * <p>Every timestamp this feature writes — a submission's {@code createdAt} and {@code updatedAt},
     * its {@code reviewedAt}, and each history row's {@code createdAt} — comes from here, so one
     * event has one time. The alternative, letting each place that records something ask for
     * {@code Instant.now()}, produces a decision whose history row is a few hundred microseconds
     * after the {@code reviewed_at} beside it, and a test that cannot say what either should be.
     *
     * <p>The {@link Clock} is a bean supplied by {@code config.ClockConfiguration} and fixed to UTC.
     * This class neither chooses the zone nor reads the JVM default — it asks for the current
     * instant and records what it is told.
     */
    private Instant now() {
        return clock.instant();
    }

    /**
     * Accepts an upload on behalf of the signed-in student.
     *
     * <p>The owner is the principal passed in, which the controller obtained from
     * {@code CurrentUserService}. There is no overload that takes a student identifier, because
     * such an overload is how a client-supplied value ends up deciding who owns a submission
     * ({@code CLAUDE.md} §7 rule 8).
     *
     * @param student    the authenticated principal
     * @param file       the uploaded part
     * @param title      the student's title for the work
     * @param courseCode the course the work belongs to
     * @return the persisted submission
     * @throws InvalidSubmissionException if the metadata or the file is not acceptable; nothing is
     *                                    stored when this is thrown
     */
    @Transactional
    public Submission submit(AuthenticatedUser student, MultipartFile file, String title,
                             String courseCode) {
        Objects.requireNonNull(student, "student");

        String acceptedTitle = requireText(title, "title", "title", MAX_TITLE_LENGTH);
        String acceptedCourseCode =
                requireText(courseCode, "course code", "courseCode", MAX_COURSE_CODE_LENGTH);

        // Validation first, and nothing above this line has touched the disk. Every rejection
        // below leaves the volume and the table exactly as they were.
        Optional<String> refusal = fileValidationService.validate(file);
        if (refusal.isPresent()) {
            // The field is `file` so the upload form can mark the file input, rather than showing
            // the sentence detached from the control it is about.
            throw new InvalidSubmissionException(refusal.get(), "file");
        }
        String originalFileName = fileValidationService.safeDisplayName(file.getOriginalFilename());

        byte[] content = readBytes(file);

        // The checksum and the size are taken from the same array that is handed to storage, so
        // the row cannot describe a file that differs from the one on the volume. This is the
        // reason the bytes are read once, here, rather than streamed separately to each step.
        String checksumSha256 = checksumService.sha256(content);

        StorageIdentifier stored = storageService.store(content);

        // Read once, used by both rows below. Reading it twice would be two calls to the Clock and
        // therefore two instants, which is exactly the disagreement this feature's history is
        // supposed to make impossible.
        Instant submittedAt = now();

        Submission submission = Submission.upload(
                student.id(),
                acceptedTitle,
                acceptedCourseCode,
                originalFileName,
                stored.value(),
                // The validated type, not file.getContentType(). The client's value is advisory
                // and may be absent entirely — FileValidationService accepts a missing one
                // deliberately — but the column is NOT NULL and both the entity and the migration
                // describe it as the type that was validated. Asking the validator for the type it
                // accepted is what makes all three agree, and removes the null path rather than
                // defending against it.
                fileValidationService.acceptedContentType(),
                content.length,
                checksumSha256,
                submittedAt);

        // Registered before the save, not after. A constraint violation is thrown by the save;
        // catching it here would cover that case but miss a failure at commit time, which happens
        // after this method has returned. The synchronization covers both, because both end in a
        // rollback.
        registerFileDiscardOnRollback(stored);

        Submission saved = submissionRepository.save(submission);

        // The creation entry (FR-15), in the same transaction as the row it describes. A history
        // that could be written separately is a history that can disagree with the submission it
        // belongs to; writing both here means either both exist or neither does — and passing
        // submittedAt to both means they also agree about when.
        statusHistoryRepository.save(
                SubmissionStatusHistory.creation(saved.getId(), student.id(), submittedAt));

        return saved;
    }

    /**
     * One page of the signed-in student's own submissions, newest first.
     *
     * <p>Takes an identifier rather than filtering a full list in memory. Passing
     * {@code findAll()} through a {@code filter} would load every student's rows into the
     * application and discard most of them — and would put the isolation rule in Java, where a
     * later change could quietly drop it.
     *
     * <p>The page is applied by the database rather than here, so the rows nobody will read are
     * never fetched. The owner is part of the query, so the page is a bound on a set that was
     * already scoped to this student and cannot widen it.
     *
     * <p>Returns {@link SubmissionSummary} rather than {@link Submission}: the list is rendered by a
     * template, the entity must not reach one ({@code SubmissionSummary} says why), and every caller
     * of this method was mapping the page itself before handing it to a view. Doing the projection
     * here means it happens once, inside the transaction that read the rows, instead of in each
     * controller — and the total the page carries is still the count of the whole list, which is
     * what the pager needs.
     */
    @Transactional(readOnly = true)
    public Page<SubmissionSummary> listForStudent(UUID studentId, Pageable pageable) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(pageable, "pageable");
        return submissionRepository
                .findByStudentIdOrderByCreatedAtDesc(studentId, pageable)
                .map(SubmissionSummary::from);
    }

    /**
     * The three counts on the student's dashboard.
     *
     * <p>Three indexed {@code COUNT} queries rather than a scan of the student's submissions: the
     * dashboard shows numbers and no rows, so fetching the rows to size them would be work the page
     * throws away. Each count carries the owner in its {@code WHERE} clause, so none can report
     * another student's work.
     */
    @Transactional(readOnly = true)
    public SubmissionCounts countsForStudent(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId");
        return new SubmissionCounts(
                submissionRepository.countByStudentIdAndStatus(studentId, SubmissionStatus.SUBMITTED),
                submissionRepository.countByStudentIdAndStatus(studentId, SubmissionStatus.APPROVED),
                submissionRepository.countByStudentIdAndStatus(studentId, SubmissionStatus.REJECTED));
    }

    /**
     * One of the signed-in student's own submissions, as the detail page shows it.
     *
     * <p>A read model rather than the entity, for the reason {@link #listForStudent} gives, and
     * because the controller that renders the page is the only caller this method has.
     *
     * @param studentId    the authenticated principal's identifier
     * @param submissionId the identifier from the URL
     * @throws SubmissionNotFoundException if no such submission exists, or it belongs to somebody
     *                                     else — the two are deliberately the same answer
     */
    @Transactional(readOnly = true)
    public SubmissionDetail detailForStudent(UUID studentId, UUID submissionId) {
        return SubmissionDetail.from(findForStudent(studentId, submissionId));
    }

    /**
     * The ownership-scoped row lookup every student-side read is built on.
     *
     * <p><strong>Private, and the only query in this class that scopes by owner.</strong> The three
     * public methods above and below it — the detail, the history, the download — differ in what
     * they do with the row, not in how they decide the caller may have it, so they share one lookup
     * rather than each writing the {@code WHERE student_id = ?} that must not be forgotten. Keeping
     * it private is what makes that structural: there is no way to obtain a {@link Submission} for a
     * student from outside this class, so the entity cannot be handed to a template by a caller that
     * has not first gone through a method built on this one.
     *
     * <p>The owner is part of the query rather than a check after the load, so somebody else's
     * submission is never read rather than read and refused.
     *
     * @throws SubmissionNotFoundException if no such submission exists, or it belongs to somebody
     *                                     else
     */
    private Submission findForStudent(UUID studentId, UUID submissionId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(submissionId, "submissionId");
        return submissionRepository.findByIdAndStudentId(submissionId, studentId)
                .orElseThrow(SubmissionNotFoundException::forRequestedSubmission);
    }

    /**
     * The student's own status history for one of their own submissions.
     *
     * <p>Scoped by owner, and the ownership check comes first: the submission is looked up with
     * {@link #findForStudent} before any history is read, so a student asking for the history of
     * somebody else's submission gets the same 404 as if it did not exist. Reading the history
     * directly by submission identifier would be the leak — the history rows carry no owner, so
     * they cannot defend themselves.
     *
     * <p>Returns {@link SubmissionHistoryEntry} — a read model with no actor on it — rather than
     * {@link SubmissionStatusHistory}, so the entity that carries {@code actorId} cannot reach a
     * template that would render it.
     *
     * @throws SubmissionNotFoundException if the submission is not this student's
     */
    @Transactional(readOnly = true)
    public List<SubmissionHistoryEntry> historyForStudent(UUID studentId, UUID submissionId) {
        findForStudent(studentId, submissionId);
        return historyFor(submissionId);
    }

    /**
     * The student's own file, for download (FR-13).
     *
     * <p>Same ordering as the history: the row is selected by owner, and only then are the bytes
     * read. The storage identifier never leaves this method.
     *
     * @throws SubmissionNotFoundException if the submission is not this student's
     */
    @Transactional(readOnly = true)
    public DownloadableFile downloadForStudent(UUID studentId, UUID submissionId) {
        return fileFor(findForStudent(studentId, submissionId));
    }

    // ------------------------------------------------------------------------
    // The review side
    //
    // These methods are NOT scoped by owner, and that is the whole difference between them and
    // the student's. A reviewer is authorised by role at the route, not by ownership of the row:
    // reviewing only one's own submissions would be meaningless. The role check lives in
    // SecurityConfiguration's /review/** rule, and the review feature reaches every one of these
    // through ReviewService rather than touching the repository
    // (docs/architecture/application-structure.md §4 rule 1).
    // ------------------------------------------------------------------------

    /**
     * One page of the reviewer's queue: everything still awaiting a decision, oldest first (FR-16).
     *
     * <p>Oldest first, unlike the student's list. A queue is worked through, so the submission that
     * has been waiting longest is the one that should be seen first; a student's list answers "what
     * did I hand in recently?" and is therefore newest first. Served by the
     * {@code (status, created_at)} index.
     *
     * <p>The status is fixed here rather than passed in, so a caller cannot ask this method for
     * decided work and page through it as though it were a queue.
     *
     * <p>Returns read models for the same reason {@link #listForStudent} does, which is also why
     * {@code ReviewService.queue} is a delegation rather than a projection.
     */
    @Transactional(readOnly = true)
    public Page<SubmissionSummary> listAwaitingReview(Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable");
        return submissionRepository
                .findByStatusOrderByCreatedAtAsc(SubmissionStatus.SUBMITTED, pageable)
                .map(SubmissionSummary::from);
    }

    /**
     * Any submission, as a reviewer sees it (FR-17).
     *
     * <p>The mirror of {@link #detailForStudent}, and different from it in exactly one way: there is
     * no owner in the query, because a reviewer is authorised by role at the route rather than by
     * holding the row. See the section note above these methods.
     *
     * @throws SubmissionNotFoundException if no submission has that identifier
     */
    @Transactional(readOnly = true)
    public SubmissionDetail detailForReview(UUID submissionId) {
        return SubmissionDetail.from(findForReview(submissionId));
    }

    /**
     * The unscoped row lookup the reviewer's reads are built on.
     *
     * <p>Private, for the same structural reason as {@link #findForStudent}: the methods that need a
     * {@link Submission} — including {@link #recordDecision}, which has to mutate one — are all in
     * this class, and nothing outside it should be able to obtain a submission without going
     * through a method that has stated what it does with it.
     *
     * @throws SubmissionNotFoundException if no submission has that identifier
     */
    private Submission findForReview(UUID submissionId) {
        Objects.requireNonNull(submissionId, "submissionId");
        return submissionRepository.findById(submissionId)
                .orElseThrow(SubmissionNotFoundException::forRequestedSubmission);
    }

    /** Any submission's status history, for a reviewer. */
    @Transactional(readOnly = true)
    public List<SubmissionHistoryEntry> historyForReview(UUID submissionId) {
        findForReview(submissionId);
        return historyFor(submissionId);
    }

    /**
     * One submission's transitions, projected for a page.
     *
     * <p>The pair of reads every history method performs, in the order they must happen: the row is
     * resolved first — which is where authorisation is decided, differently for each side — and only
     * then the rows that hang off it are read. Shared so that the ordering cannot be established in
     * one place and reversed in the other.
     */
    private List<SubmissionHistoryEntry> historyFor(UUID submissionId) {
        return statusHistoryRepository.findBySubmissionIdOrderByCreatedAtAsc(submissionId).stream()
                .map(SubmissionHistoryEntry::from)
                .toList();
    }

    /** Any submission's file, for a reviewer to read before deciding (FR-17). */
    @Transactional(readOnly = true)
    public DownloadableFile downloadForReview(UUID submissionId) {
        return fileFor(findForReview(submissionId));
    }

    /**
     * Applies a reviewer's decision and records it in the history (FR-18, FR-20, FR-21).
     *
     * <p>This is the <em>mechanical</em> half of a decision: the status transition, the two
     * decision columns, and the history row. The <em>policy</em> — that a rejection must carry a
     * reason and an approval must not — belongs to {@code ReviewService}, which calls this. The
     * split is what the dependency rules require: the review feature may not mutate a
     * {@link Submission} or touch its repository, and this feature has no business knowing what a
     * reviewer's comment rules are.
     *
     * <h2>Three guarantees, one transaction</h2>
     *
     * <ol>
     *   <li><strong>The submission is re-read inside this transaction.</strong> The caller passes
     *       an identifier, not an entity, so the terminality check below runs against the row as it
     *       is now rather than as some earlier request saw it.</li>
     *   <li><strong>Terminality is enforced by the entity.</strong> {@link Submission#approve} and
     *       {@link Submission#reject} throw {@link SubmissionAlreadyDecidedException} for a row
     *       that is not {@code SUBMITTED}, which is what refuses a second approval, a second
     *       rejection, and a decision on already-decided work.</li>
     *   <li><strong>The status change and the history row commit together.</strong> Both writes are
     *       inside this method's transaction, so a failure in either rolls back both. A decision
     *       recorded with no history, or a history entry for a decision that was never applied,
     *       cannot be left behind.</li>
     * </ol>
     *
     * <p>The optimistic lock is the fourth guarantee and the one this method does not have to
     * implement: {@code Submission.version} is a {@code @Version} column, so if two reviewers
     * decide the same submission concurrently the second commit updates nothing and Spring
     * translates that into an {@code ObjectOptimisticLockingFailureException}. The controller turns
     * it into a message. Nobody's decision is silently overwritten.
     *
     * <p>Nothing is returned. A decision is terminal (FR-20), so the decided state can only be
     * re-read and never acted on again, and the caller that used to take the row — the reviewer's
     * controller — discarded it. Returning something no caller uses invites a caller to treat the
     * in-transaction instance as authoritative after the transaction has closed.
     *
     * @param reviewerId   the deciding reviewer, from the authenticated principal
     * @param submissionId the submission to decide
     * @param outcome      {@code APPROVED} or {@code REJECTED}
     * @param note         the reviewer's comment; required for a rejection, {@code null} otherwise
     * @throws SubmissionNotFoundException        if no submission has that identifier
     * @throws SubmissionAlreadyDecidedException  if it has already been decided
     * @throws IllegalArgumentException           if {@code outcome} is a status that is not a
     *                                            decision, which is a defect in the caller rather
     *                                            than something a reviewer can cause
     */
    @Transactional
    public void recordDecision(UUID reviewerId, UUID submissionId,
                               SubmissionStatus outcome, String note) {
        Objects.requireNonNull(reviewerId, "reviewerId");
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(outcome, "outcome");

        // SUBMITTED is a status but not a decision, and this is the vocabulary's own answer to
        // "which statuses are decisions?" rather than a pair of literals restated here. It was
        // previously a switch on the three constants, which meant the same rule existed in two
        // places — this method and ReviewService — and would have had to be changed in both when a
        // fourth status arrived. ReviewService refuses this first, with a sentence for the
        // reviewer; this is the assertion underneath it, hence the exception rather than a
        // rendered message.
        if (!outcome.isDecision()) {
            throw new IllegalArgumentException("A decision must be APPROVED or REJECTED.");
        }

        Submission submission = findForReview(submissionId);
        SubmissionStatus previousStatus = submission.getStatus();

        // One instant for the transition, used by the row's reviewed_at, its updated_at, and the
        // history entry that records the same event. Read once, above the write.
        Instant decidedAt = now();
        if (outcome == SubmissionStatus.APPROVED) {
            submission.approve(reviewerId, decidedAt);
        } else {
            submission.reject(reviewerId, note, decidedAt);
        }

        submissionRepository.save(submission);

        statusHistoryRepository.save(SubmissionStatusHistory.transition(
                submission.getId(), reviewerId, previousStatus, outcome, note, decidedAt));
    }

    /**
     * Reads the bytes a submission row describes.
     *
     * <p>Private, and takes the entity: every public caller above has already decided whether the
     * requester may see this row, and this method is the step after that decision rather than a
     * second way to make it.
     */
    private DownloadableFile fileFor(Submission submission) {
        byte[] content = storageService.load(
                new StorageIdentifier(submission.getStoredFileName()));

        return new DownloadableFile(
                submission.getOriginalFileName(),
                submission.getContentType(),
                content);
    }

    /**
     * Arranges for the stored file to be removed if the surrounding transaction does not commit.
     *
     * <p>A {@code try}/{@code catch} around the save would not be enough. {@code save} ends the
     * method's work but not the transaction's: the commit happens later, in the transaction
     * manager, after this method has returned. A failure there — a deferred constraint, a
     * connection lost at commit — would roll the row back with no code of ours left to run, and
     * the file would stay behind. A transaction synchronization is invoked on the way out of the
     * transaction whichever way it ends, so it catches both the failure that happens inside this
     * method and the one that happens after it.
     *
     * <p>{@code STATUS_COMMITTED} is the only outcome that leaves the file alone. A rollback and
     * an unknown status both mean the row is not there, and a file without a row is the orphan
     * this method exists to prevent.
     *
     * <p>If no transaction is active, there is nothing that can roll back and therefore nothing to
     * undo; the guard is not defensive coding but a statement of that reasoning.
     */
    private void registerFileDiscardOnRollback(StorageIdentifier stored) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    discardAfterRollback(stored);
                }
            }
        });
    }

    /**
     * Removes a file whose row was rolled back.
     *
     * <p>Never throws. The transaction has already failed, and the exception the caller is about
     * to see must be that failure — not a secondary one raised while tidying up after it. If the
     * removal itself fails, the identifier is logged so an operator can delete the file by hand;
     * that is a worse outcome than a silent success and a better one than an obscured root cause.
     */
    private void discardAfterRollback(StorageIdentifier stored) {
        try {
            storageService.delete(stored);
            log.warn("Removed the stored file for a submission whose transaction did not commit.");
        } catch (RuntimeException cleanupFailure) {
            log.error("Could not remove the orphaned upload file {}. It must be deleted by hand.",
                    stored.value(), cleanupFailure);
        }
    }

    /** Reads the upload into memory, refusing rather than failing if the bytes cannot be read. */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException cause) {
            // Nothing has been stored at this point, so this is a clean refusal rather than a
            // partial write. The cause is not shown to the student — it would name a temporary
            // file path — but it is recorded for whoever has to work out what happened.
            log.warn("An uploaded file could not be read; refusing it.", cause);
            throw new InvalidSubmissionException(
                    "The uploaded file could not be read. Please try again.");
        }
    }

    /**
     * Trims and bounds a text field, refusing rather than truncating.
     *
     * <p>Truncation would silently store something the student did not write. A refusal with a
     * message lets them fix it — and the column width is not a secret worth protecting.
     *
     * <p>Two names for the same field, and they are not redundant: {@code fieldName} is the words
     * for the sentence the student reads ("course code"), and {@code field} is the form control's
     * own name ("courseCode"), which the template uses to mark the offending input. They differ
     * because a sentence and an input name are written for different readers.
     */
    private String requireText(String value, String fieldName, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidSubmissionException("A " + fieldName + " is required.", field);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidSubmissionException(
                    "The " + fieldName + " may be at most " + maxLength + " characters.", field);
        }
        return trimmed;
    }
}
