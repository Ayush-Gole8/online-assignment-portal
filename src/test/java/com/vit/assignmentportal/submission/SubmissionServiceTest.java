package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.storage.ChecksumService;
import com.vit.assignmentportal.storage.FileValidationService;
import com.vit.assignmentportal.storage.StorageIdentifier;
import com.vit.assignmentportal.storage.StorageService;
import com.vit.assignmentportal.storage.UploadProperties;
import com.vit.assignmentportal.support.SubmissionTestData;
import com.vit.assignmentportal.support.TestCategories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

/**
 * The submission service's <em>orchestration</em>: what it writes, in what order, and with which
 * single instant — the behaviour that is a property of how the collaborators are driven rather than
 * of any one of them.
 *
 * <h2>Why this is a unit test and not another {@code *IT}</h2>
 *
 * <p>The integration suite already proves that an upload ends up on the volume and in MySQL. What it
 * cannot state cheaply is the exact contract between the service and its collaborators: that the
 * checksum is taken from the same array that is handed to storage, that the row and its creation
 * history entry carry one instant rather than two reads of a clock, that a refused upload touches
 * neither the volume nor the database. Those are all visible here in milliseconds, and this class is
 * where each is pinned.
 *
 * <h2>A real validator and a real checksum, fake boundaries</h2>
 *
 * <p>{@link FileValidationService} and {@link ChecksumService} are the real implementations, because
 * they are pure and a fake would let the service's contract agree with a fake rather than with the
 * code that ships. Only the two persistence repositories and {@link StorageService} are mocked — the
 * three places that reach outside the JVM, which is exactly what a unit test may not do. There is no
 * over-mocking to hide behind: the file that is validated really is validated, and the digest that is
 * asserted really is computed.
 *
 * <h2>The clock is fixed</h2>
 *
 * <p>The service takes a {@link Clock}, so {@code now} is a value this test chooses. Every timestamp
 * asserted below is that value. A test that tolerated "some time around when the test ran" would
 * neither notice a second, later clock read nor be able to state which instant an event should carry
 * — and the one-instant rule is the whole reason the clock is injected.
 */
@Tag(TestCategories.UNIT)
class SubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The committed defaults, so the real validator accepts a real PDF at the real size limit. */
    private static final UploadProperties UPLOAD_PROPERTIES =
            new UploadProperties(DataSize.ofMegabytes(10), "application/pdf");

    private static final UUID STUDENT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REVIEWER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final StorageIdentifier STORED =
            new StorageIdentifier("44444444-4444-4444-8444-444444444444");

    private static final AuthenticatedUser STUDENT = new AuthenticatedUser(
            STUDENT_ID, "student@example.com", "$2a$10$notarealhash", Role.STUDENT, true);

    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final SubmissionStatusHistoryRepository historyRepository =
            mock(SubmissionStatusHistoryRepository.class);
    private final StorageService storageService = mock(StorageService.class);

    private final SubmissionService service = new SubmissionService(
            submissionRepository,
            historyRepository,
            storageService,
            new FileValidationService(UPLOAD_PROPERTIES),
            new ChecksumService(),
            CLOCK);

    // ------------------------------------------------------------------------
    // submit
    // ------------------------------------------------------------------------

    /**
     * A valid upload is stored, checksummed from the bytes that were stored, and described by a row
     * and a creation history entry that agree about the instant.
     *
     * <p>The row's fields are asserted against the bytes the test itself handed in, not against the
     * service's own intermediate values: the checksum is compared with
     * {@link SubmissionTestData#sha256Hex} — an independent digest of the same input — so a service
     * that hashed something other than what it stored fails here.
     */
    @Test
    void storesTheBytesAndWritesTheRowWithItsCreationEntry() {
        when(storageService.store(any())).thenReturn(STORED);
        when(submissionRepository.save(any(Submission.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

        Submission saved = service.submit(STUDENT, SubmissionTestData.pdf(), "  Essay one  ",
                " CS101 ");

        assertThat(saved.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(saved.getTitle()).as("surrounding whitespace is trimmed").isEqualTo("Essay one");
        assertThat(saved.getCourseCode()).isEqualTo("CS101");
        assertThat(saved.getContentType()).isEqualTo(SubmissionTestData.PDF_CONTENT_TYPE);
        assertThat(saved.getFileSizeBytes()).isEqualTo(SubmissionTestData.PDF_BYTES.length);
        assertThat(saved.getChecksumSha256())
                .as("the checksum describes the bytes that were stored")
                .isEqualTo(SubmissionTestData.sha256Hex(SubmissionTestData.PDF_BYTES));
        assertThat(saved.getStoredFileName())
                .as("the row names the server-generated identifier, never the uploaded name")
                .isEqualTo(STORED.value());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(saved.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);

        // The bytes handed to storage are the same array that was checksummed, which is the property
        // that keeps the row from describing a file that differs from the one on the volume.
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).store(storedBytes.capture());
        assertThat(storedBytes.getValue()).isEqualTo(SubmissionTestData.PDF_BYTES);

        SubmissionStatusHistory creation = capturedHistoryRow();
        assertThat(creation.getSubmissionId()).isEqualTo(saved.getId());
        assertThat(creation.getFromStatus()).as("the creation row begins the lifecycle").isNull();
        assertThat(creation.getToStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(creation.getActorId()).isEqualTo(STUDENT_ID);
        assertThat(creation.getCreatedAt())
                .as("the history entry carries the same instant as the row it describes")
                .isEqualTo(NOW);
    }

    /**
     * A refused file reaches neither the volume nor the database.
     *
     * <p>This is the ordering rule in {@code submit} stated as an assertion: validation sits above
     * storage, so the common failure — somebody picking the wrong file — costs nothing. The negative
     * assertion on all three collaborators is what makes it meaningful; checking only the thrown
     * exception would pass if the file had been written and then deleted.
     */
    @Test
    void writesNothingWhenTheFileIsRefused() {
        InvalidSubmissionException refusal = refuse(() ->
                service.submit(STUDENT, SubmissionTestData.notAPdf(), "Essay one", "CS101"));

        assertThat(refusal.field()).isEqualTo("file");
        verifyNoInteractions(storageService, submissionRepository, historyRepository);
    }

    /** A missing title is refused before anything is stored. */
    @Test
    void refusesAnEmptyTitle() {
        InvalidSubmissionException refusal = refuse(() ->
                service.submit(STUDENT, SubmissionTestData.pdf(), "   ", "CS101"));

        assertThat(refusal.field()).isEqualTo("title");
        verifyNoInteractions(storageService, submissionRepository, historyRepository);
    }

    /** A title wider than the column is refused rather than truncated. */
    @Test
    void refusesAnOverlongTitle() {
        InvalidSubmissionException refusal = refuse(() ->
                service.submit(STUDENT, SubmissionTestData.pdf(), "x".repeat(201), "CS101"));

        assertThat(refusal.field()).isEqualTo("title");
        verifyNoInteractions(storageService, submissionRepository, historyRepository);
    }

    /** A course code wider than the column is refused with a message naming the field. */
    @Test
    void refusesAnOverlongCourseCode() {
        InvalidSubmissionException refusal = refuse(() ->
                service.submit(STUDENT, SubmissionTestData.pdf(), "Essay one", "x".repeat(21)));

        assertThat(refusal.field()).isEqualTo("courseCode");
        verifyNoInteractions(storageService, submissionRepository, historyRepository);
    }

    // ------------------------------------------------------------------------
    // recordDecision
    // ------------------------------------------------------------------------

    /**
     * A decision moves the row and appends the transition, both with the same instant.
     *
     * <p>The history row is asserted as well as the row itself: a decision that changed the status
     * but left no audit entry is the failure the append-only table exists to make impossible, and a
     * test that read only the status column would not see it.
     */
    @Test
    void recordsADecisionAndAppendsTheTransition() {
        Submission submission = storedSubmission();
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));

        service.recordDecision(REVIEWER_ID, SUBMISSION_ID, SubmissionStatus.APPROVED, null);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(submission.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(submission.getReviewedAt()).isEqualTo(NOW);
        assertThat(submission.getRejectionReason()).isNull();

        SubmissionStatusHistory transition = capturedHistoryRow();
        assertThat(transition.getFromStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(transition.getToStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(transition.getActorId()).isEqualTo(REVIEWER_ID);
        assertThat(transition.getNote()).as("an approval carries no note").isNull();
        assertThat(transition.getCreatedAt())
                .as("the transition and the row carry one instant, not two reads of the clock")
                .isEqualTo(NOW);
    }

    /**
     * {@code SUBMITTED} is a status but not a decision, and this is the assertion underneath the
     * reviewer's policy.
     *
     * <p>It is an {@link IllegalArgumentException} rather than a {@code PortalException} on purpose:
     * the reviewer's service refuses this first with a sentence, so reaching this one is a defect in
     * a caller and not something a reviewer can cause.
     */
    @Test
    void refusesAStatusThatIsNotADecision() {
        assertThatThrownBy(() -> service.recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.SUBMITTED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED or REJECTED");

        verifyNoInteractions(submissionRepository, historyRepository);
    }

    /** A rejection with no reason is refused, and nothing is written. */
    @Test
    void refusesToRecordARejectionWithoutAReason() {
        when(submissionRepository.findById(SUBMISSION_ID))
                .thenReturn(Optional.of(storedSubmission()));

        assertThatThrownBy(() -> service.recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(historyRepository, never()).save(any());
    }

    /** A submission that already carries a decision cannot be decided again (FR-20). */
    @Test
    void refusesADecisionOnAlreadyDecidedWork() {
        Submission decided = storedSubmission();
        decided.approve(REVIEWER_ID, NOW);
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> service.recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.APPROVED, null))
                .isInstanceOf(SubmissionAlreadyDecidedException.class);

        verify(historyRepository, never()).save(any());
    }

    /** An identifier nobody holds is refused the same way a student's missing row is. */
    @Test
    void refusesADecisionForAnUnknownSubmission() {
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.APPROVED, null))
                .isInstanceOf(SubmissionNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // Owner-scoped reads
    // ------------------------------------------------------------------------

    /**
     * The student's detail lookup carries the owner in the query.
     *
     * <p>The assertion is on the arguments the repository was called with, because that is the whole
     * design claim: somebody else's submission is never loaded rather than loaded and refused. A test
     * that only asserted the returned detail would not distinguish the two.
     */
    @Test
    void scopesTheStudentLookupByOwner() {
        when(submissionRepository.findByIdAndStudentId(SUBMISSION_ID, STUDENT_ID))
                .thenReturn(Optional.of(SubmissionTestData.draft(STUDENT_ID, NOW)));

        SubmissionDetail detail = service.detailForStudent(STUDENT_ID, SUBMISSION_ID);

        assertThat(detail.title()).isEqualTo(SubmissionTestData.DEFAULT_TITLE);
        verify(submissionRepository).findByIdAndStudentId(SUBMISSION_ID, STUDENT_ID);
    }

    /** A row the owner-scoped query does not return is reported as not found, not as another's work. */
    @Test
    void reportsAnotherStudentsSubmissionAsNotFound() {
        when(submissionRepository.findByIdAndStudentId(SUBMISSION_ID, STUDENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detailForStudent(STUDENT_ID, SUBMISSION_ID))
                .isInstanceOf(SubmissionNotFoundException.class);
    }

    /**
     * The file bytes are read only after the owner-scoped row lookup has succeeded.
     *
     * <p>Two things are asserted: the returned record carries the display name and the validated
     * type rather than the storage identifier, and the bytes come from the volume as they were
     * stored. The identifier never leaves the service.
     */
    @Test
    void readsTheFileOnlyAfterTheOwnerScopedLookup() {
        Submission stored = storedSubmission();
        when(submissionRepository.findByIdAndStudentId(SUBMISSION_ID, STUDENT_ID))
                .thenReturn(Optional.of(stored));
        when(storageService.load(STORED)).thenReturn(SubmissionTestData.PDF_BYTES);

        DownloadableFile file = service.downloadForStudent(STUDENT_ID, SUBMISSION_ID);

        assertThat(file.fileName()).isEqualTo(SubmissionTestData.DEFAULT_FILE_NAME);
        assertThat(file.contentType()).isEqualTo(SubmissionTestData.PDF_CONTENT_TYPE);
        assertThat(file.content()).isEqualTo(SubmissionTestData.PDF_BYTES);
        verify(storageService).load(STORED);
    }

    // ------------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------------

    /**
     * Runs an upload that must be refused and returns the refusal.
     *
     * <p>A plain try/catch rather than an assertion-library throwable matcher, so that the refusal's
     * own accessor can be read without a cast inside a lambda. The upload declares no checked
     * exception, so a {@link Runnable} is all the shape this needs.
     */
    private static InvalidSubmissionException refuse(Runnable upload) {
        try {
            upload.run();
        } catch (InvalidSubmissionException refusal) {
            return refusal;
        }
        throw new AssertionError("Expected the upload to be refused, but it was accepted.");
    }

    /** A submission row with the identifier this class addresses, as if it had been inserted. */
    private static Submission storedSubmission() {
        Submission submission = Submission.upload(
                STUDENT_ID,
                SubmissionTestData.DEFAULT_TITLE,
                SubmissionTestData.DEFAULT_COURSE_CODE,
                SubmissionTestData.DEFAULT_FILE_NAME,
                STORED.value(),
                SubmissionTestData.PDF_CONTENT_TYPE,
                SubmissionTestData.PDF_BYTES.length,
                SubmissionTestData.sha256Hex(SubmissionTestData.PDF_BYTES),
                NOW);
        // Assigns the identifier the way persistence does, so the history row written against it
        // names a real submission rather than null.
        submission.onInsert();
        return submission;
    }

    /** Assigns an identifier to a row handed back by a stubbed {@code save}, as an insert would. */
    private static Submission persisted(Submission submission) {
        submission.onInsert();
        return submission;
    }

    /** The one history row the service wrote, captured so its fields can be asserted. */
    private SubmissionStatusHistory capturedHistoryRow() {
        ArgumentCaptor<SubmissionStatusHistory> captor =
                ArgumentCaptor.forClass(SubmissionStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }
}
