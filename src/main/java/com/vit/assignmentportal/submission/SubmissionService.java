package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.storage.ChecksumService;
import com.vit.assignmentportal.storage.FileValidationService;
import com.vit.assignmentportal.storage.StorageIdentifier;
import com.vit.assignmentportal.storage.StorageService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final StorageService storageService;
    private final FileValidationService fileValidationService;
    private final ChecksumService checksumService;

    public SubmissionService(SubmissionRepository submissionRepository,
                             StorageService storageService,
                             FileValidationService fileValidationService,
                             ChecksumService checksumService) {
        this.submissionRepository = submissionRepository;
        this.storageService = storageService;
        this.fileValidationService = fileValidationService;
        this.checksumService = checksumService;
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

        String acceptedTitle = requireText(title, "title", MAX_TITLE_LENGTH);
        String acceptedCourseCode = requireText(courseCode, "course code", MAX_COURSE_CODE_LENGTH);

        // Validation first, and nothing above this line has touched the disk. Every rejection
        // below leaves the volume and the table exactly as they were.
        Optional<String> refusal = fileValidationService.validate(file);
        if (refusal.isPresent()) {
            throw new InvalidSubmissionException(refusal.get());
        }
        String originalFileName = fileValidationService.safeDisplayName(file.getOriginalFilename());

        byte[] content = readBytes(file);

        // The checksum and the size are taken from the same array that is handed to storage, so
        // the row cannot describe a file that differs from the one on the volume. This is the
        // reason the bytes are read once, here, rather than streamed separately to each step.
        String checksumSha256 = checksumService.sha256(content);

        StorageIdentifier stored = storageService.store(content);

        Submission submission = Submission.upload(
                student.id(),
                acceptedTitle,
                acceptedCourseCode,
                originalFileName,
                stored.value(),
                // The validated type, not file.getContentType(). The client's value is advisory
                // and may be absent entirely — FileValidationService accepts a missing one
                // deliberately — but the column is NOT NULL and both the entity and the migration
                // describe it as the type that was validated. Recording the constant is what makes
                // all three agree, and removes the null path rather than defending against it.
                FileValidationService.PDF_CONTENT_TYPE,
                content.length,
                checksumSha256);

        // Registered before the save, not after. A constraint violation is thrown by the save;
        // catching it here would cover that case but miss a failure at commit time, which happens
        // after this method has returned. The synchronization covers both, because both end in a
        // rollback.
        registerFileDiscardOnRollback(stored);

        return submissionRepository.save(submission);
    }

    /**
     * The signed-in student's own submissions, newest first.
     *
     * <p>Takes an identifier rather than filtering a full list in memory. Passing
     * {@code findAll()} through a {@code filter} would load every student's rows into the
     * application and discard most of them — and would put the isolation rule in Java, where a
     * later change could quietly drop it.
     */
    @Transactional(readOnly = true)
    public List<Submission> listForStudent(UUID studentId) {
        Objects.requireNonNull(studentId, "studentId");
        return submissionRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    /**
     * One of the signed-in student's own submissions.
     *
     * @param studentId    the authenticated principal's identifier
     * @param submissionId the identifier from the URL
     * @return the submission
     * @throws SubmissionNotFoundException if no such submission exists, or it belongs to somebody
     *                                     else — the two are deliberately the same answer
     */
    @Transactional(readOnly = true)
    public Submission findForStudent(UUID studentId, UUID submissionId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(submissionId, "submissionId");
        return submissionRepository.findByIdAndStudentId(submissionId, studentId)
                .orElseThrow(SubmissionNotFoundException::forRequestedSubmission);
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
     */
    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidSubmissionException("A " + fieldName + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidSubmissionException(
                    "The " + fieldName + " may be at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
