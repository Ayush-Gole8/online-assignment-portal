package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.DatabaseUserDetailsService;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.storage.ChecksumService;
import com.vit.assignmentportal.storage.FileValidationService;
import com.vit.assignmentportal.storage.StorageException;
import com.vit.assignmentportal.storage.StorageIdentifier;
import com.vit.assignmentportal.storage.StorageProperties;
import com.vit.assignmentportal.storage.StorageService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What the two-phase write does when one of the two phases fails.
 *
 * <p>There is no distributed transaction between the volume and MySQL, so the ordering in
 * {@code SubmissionService} is the design: validate, write the bytes, register a transaction
 * synchronization, then write the row. This class exercises each way that can end badly, and — just
 * as importantly — the case where it ends well, because a cleanup step that always deleted would
 * pass every failure test in this file and destroy every successful upload.
 *
 * <p>Two seams are mocked, and no more: {@link SubmissionRepository}, because "the database refused
 * the write" cannot be arranged through the application's own validation — every value that reaches
 * {@code save} has already been checked — and {@link SubmissionStatusHistoryRepository}, which is
 * written in the same method and would otherwise try to insert a history row for a submission the
 * stubbed repository never gave an identifier. The volume is real throughout: a mocked filesystem
 * would accept whatever the test told it to, and every assertion below is about what is actually on
 * the disk.
 *
 * <p>The failure that remains untestable is the process dying between the write and the commit. No
 * code runs in that case, so the file is orphaned; that is documented as a known limitation rather
 * than asserted here.
 */
@SpringBootTest
class SubmissionFailureHandlingIT extends MySqlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private UserService userService;

    @Autowired
    private DatabaseUserDetailsService userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SubmissionRepository submissionRepository;

    /**
     * Mocked alongside {@link SubmissionRepository}, because {@code submit} now writes a creation
     * row into it inside the same transaction.
     *
     * <p>The mock is not a convenience. With {@code SubmissionRepository.save} stubbed, the entity
     * that comes back has no identifier — no {@code @PrePersist} ran — so a real history repository
     * would be asked to insert a row whose {@code submission_id} is null and would fail the foreign
     * key. The test is about where the <em>file</em> ends up, so the second write is stubbed out
     * rather than allowed to raise an unrelated failure.
     */
    @MockitoBean
    private SubmissionStatusHistoryRepository statusHistoryRepository;

    /**
     * The file is written and then the row fails: the file must be gone.
     *
     * <p>Both halves are asserted, and the first is what makes the test worth having. Checking only
     * that nothing remains would pass just as well if nothing had ever been written — a test that
     * reports success for a code path it never reached. Recording the file count from inside the
     * failing {@code save} pins the ordering: the write provably happened, so the removal provably
     * happened too.
     */
    @Test
    void removesTheStoredFileWhenTheRowCannotBeWritten() {
        AuthenticatedUser student = studentFor("failure-rollback");
        List<Path> before = storedFiles();
        AtomicInteger filesOnDiskWhenTheSaveWasAttempted = new AtomicInteger(-1);

        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            filesOnDiskWhenTheSaveWasAttempted.set(storedFiles().size());
            throw new DataIntegrityViolationException("simulated: the row could not be written");
        });

        assertThatThrownBy(() -> submissionService.submit(student, pdf(), "Essay one", "CS101"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(filesOnDiskWhenTheSaveWasAttempted.get())
                .as("the file must have been written before the row was attempted")
                .isEqualTo(before.size() + 1);
        assertThat(storedFiles())
                .as("the file written for a rolled-back row must be removed")
                .isEqualTo(before);
        assertThat(rowsFor(student)).isEmpty();
    }

    /**
     * The control case, and the one that keeps the test above honest.
     *
     * <p>If the cleanup deleted unconditionally, every upload would still appear to work — the row
     * would be there and the redirect would happen — and the artefact would be gone. That failure
     * passes any suite that only tests the rollback path.
     */
    @Test
    void keepsTheStoredFileWhenTheRowCommits() {
        AuthenticatedUser student = studentFor("failure-commit");
        List<Path> before = storedFiles();

        when(submissionRepository.save(any(Submission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Submission submission = submissionService.submit(student, pdf(), "Essay one", "CS101");

        assertThat(storedFiles()).hasSize(before.size() + 1);
        assertThat(storedFiles()).anyMatch(path ->
                path.getFileName().toString().equals(submission.getStoredFileName()));
    }

    /**
     * The volume is unusable: no row is written and nothing is stored.
     *
     * <p>The volume fails for a real reason rather than a mocked one. The storage root here is an
     * ordinary file, so {@code Files.createDirectories} refuses to turn it into a directory and the
     * write fails the way a full or unmounted disk would. A {@code StorageException} is exactly
     * what an operator should see.
     */
    @Test
    void writesNoRowAndStoresNothingWhenTheVolumeIsUnusable(@TempDir Path scratch) throws Exception {
        AuthenticatedUser student = studentFor("failure-volume");
        List<Path> before = storedFiles();

        Path blockedRoot = Files.createFile(scratch.resolve("not-a-directory"));
        SubmissionService serviceWithAnUnusableVolume = new SubmissionService(
                submissionRepository,
                statusHistoryRepository,
                new StorageService(new StorageProperties(blockedRoot)),
                new FileValidationService(),
                new ChecksumService());

        assertThatThrownBy(() -> serviceWithAnUnusableVolume.submit(student, pdf(), "Essay one", "CS101"))
                .isInstanceOf(StorageException.class);

        assertThat(storedFiles())
                .as("nothing may be written to the real volume either")
                .isEqualTo(before);
        assertThat(rowsFor(student)).isEmpty();
        verify(submissionRepository, never()).save(any(Submission.class));
    }

    /**
     * A refused upload never reaches storage, so there is nothing to clean up.
     *
     * <p>This is the property that makes the ordering in {@code SubmissionService} worth its
     * comment: validation sits above the storage call, so the common failure — somebody picking the
     * wrong file — costs no disk write at all.
     */
    @Test
    void neverWritesAFileOrARowForARefusedUpload() {
        AuthenticatedUser student = studentFor("failure-refused");
        List<Path> before = storedFiles();

        MockMultipartFile notAPdf =
                new MockMultipartFile("file", "essay.docx", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> submissionService.submit(student, notAPdf, "Essay one", "CS101"))
                .isInstanceOf(InvalidSubmissionException.class);

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(rowsFor(student)).isEmpty();
        verify(submissionRepository, never()).save(any(Submission.class));
    }

    /**
     * When the cleanup itself fails, the caller still sees the original failure.
     *
     * <p>{@code discardAfterRollback} runs while another failure is already being reported, and its
     * job is to not make things worse. The assertion is on the exception <em>type</em>: if the
     * cleanup's {@code StorageException} were allowed to propagate, the operator would be told the
     * disk was the problem and never learn that the row was refused.
     *
     * <p>The second assertion is the honest cost of that choice. The file stays behind, because
     * nothing could remove it; the service logs the identifier so an operator can delete it by
     * hand. A test that checked only the exception would quietly imply the volume was tidy.
     */
    @Test
    void reportsTheOriginalFailureWhenTheCleanupCannotRemoveTheFile(@TempDir Path scratch) {
        AuthenticatedUser student = studentFor("failure-cleanup");
        SubmissionService serviceWithAnUndeletableVolume = new SubmissionService(
                submissionRepository,
                statusHistoryRepository,
                new UndeletableStorageService(scratch),
                new FileValidationService(),
                new ChecksumService());

        when(submissionRepository.save(any(Submission.class)))
                .thenThrow(new DataIntegrityViolationException("simulated: the row was refused"));

        assertThatThrownBy(() ->
                serviceWithAnUndeletableVolume.submit(student, pdf(), "Essay one", "CS101"))
                .as("the database failure is the one the caller must be told about")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(storedFilesIn(scratch))
                .as("the orphan the cleanup could not remove is left, and is logged")
                .hasSize(1);
        assertThat(rowsFor(student)).isEmpty();
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "essay.pdf", "application/pdf", PDF_BYTES);
    }

    /**
     * A real principal for a real account.
     *
     * <p>Built through the same service Spring Security uses rather than constructed by hand: a
     * hand-built {@code AuthenticatedUser} could carry an identifier no row references, and the
     * foreign key would then fail for a reason unrelated to what is under test.
     */
    private AuthenticatedUser studentFor(String slug) {
        String email = slug + "@example.com";
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, Role.STUDENT);
        }
        return (AuthenticatedUser) userDetailsService.loadUserByUsername(email);
    }

    private List<String> rowsFor(AuthenticatedUser student) {
        return jdbcTemplate.queryForList("SELECT id FROM submissions WHERE student_id = ?",
                String.class, student.id().toString());
    }

    private static List<Path> storedFiles() {
        return storedFilesIn(storageRoot());
    }

    private static List<Path> storedFilesIn(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("Could not list " + directory, cause);
        }
    }

    /**
     * A volume that writes normally and cannot be cleaned up.
     *
     * <p>A subclass rather than a Mockito spy, so the behaviour it fakes is visible: {@code store}
     * is the real implementation, and only {@code delete} is replaced. That is the shape of the
     * failure being modelled — the write worked, the removal did not.
     */
    private static final class UndeletableStorageService extends StorageService {

        UndeletableStorageService(Path root) {
            super(new StorageProperties(root));
        }

        @Override
        public void delete(StorageIdentifier identifier) {
            throw new StorageException("simulated: the stored file could not be removed",
                    new IOException("simulated I/O failure"));
        }
    }
}
