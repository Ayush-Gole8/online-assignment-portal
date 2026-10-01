package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file I/O, against a real directory that is thrown away after each test.
 *
 * <p>{@link TempDir} rather than a mocked {@code FileSystem}: every guarantee this class makes is a
 * statement about what a filesystem does — that an atomic rename leaves either nothing or the
 * whole file, that a directory is created when it is missing, that a second delete does not fail.
 * A mock would return whatever the test told it to return and prove none of it.
 *
 * <p>A better test would also kill the process between the write and the rename to prove the
 * atomicity claim. That is not achievable in-process, so it is stated here as a known gap rather
 * than asserted falsely in the test body.
 */
class StorageServiceTest {

    private static final byte[] CONTENT = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void storesTheBytesUnderTheIdentifierItReturns() throws IOException {
        StorageService storage = storageAt(tempDir);

        StorageIdentifier identifier = storage.store(CONTENT);

        Path stored = tempDir.resolve(identifier.value());
        assertThat(stored).isRegularFile();
        assertThat(Files.readAllBytes(stored)).isEqualTo(CONTENT);
    }

    /**
     * The contract the submission metadata depends on: the value in {@code stored_file_name} names
     * the artefact, and nothing else in the volume does.
     */
    @Test
    void leavesOnDiskNothingButTheFileItNames() throws IOException {
        StorageService storage = storageAt(tempDir);

        StorageIdentifier identifier = storage.store(CONTENT);

        assertThat(filesIn(tempDir)).containsExactly(identifier.value());
    }

    @Test
    void storesEachUploadSeparatelyEvenWhenTheBytesAreIdentical() throws IOException {
        StorageService storage = storageAt(tempDir);

        StorageIdentifier first = storage.store(CONTENT);
        StorageIdentifier second = storage.store(CONTENT);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(filesIn(tempDir)).containsExactlyInAnyOrder(first.value(), second.value());
    }

    @Test
    void createsTheStorageRootWhenItDoesNotExistYet() {
        Path missingRoot = tempDir.resolve("volume").resolve("uploads");
        StorageService storage = storageAt(missingRoot);

        StorageIdentifier identifier = storage.store(CONTENT);

        assertThat(missingRoot.resolve(identifier.value())).isRegularFile();
    }

    /**
     * The atomic write goes through a temporary file in the same directory. If that file survived,
     * an operator listing the volume would see an artefact with no row pointing at it — and, worse,
     * a later upload could be confused with it.
     */
    @Test
    void leavesNoIntermediateFileBehind() throws IOException {
        StorageService storage = storageAt(tempDir);

        storage.store(CONTENT);

        assertThat(filesIn(tempDir)).allMatch(name -> !name.endsWith(".part"));
    }

    @Test
    void deletesAStoredFile() {
        StorageService storage = storageAt(tempDir);
        StorageIdentifier identifier = storage.store(CONTENT);

        storage.delete(identifier);

        assertThat(tempDir.resolve(identifier.value())).doesNotExist();
    }

    /**
     * The idempotency the rollback path relies on. {@code SubmissionService} removes the file when
     * the database write fails, and it may run after a failure that happened before the file was
     * written at all. A cleanup step that threw here would replace a handled failure with an
     * unhandled one, in the one code path that is already reporting a problem.
     */
    @Test
    void deletingSomethingThatIsNotThereIsNotAnError() {
        StorageService storage = storageAt(tempDir);
        StorageIdentifier identifier = StorageIdentifier.generate();

        assertThatCode(() -> storage.delete(identifier)).doesNotThrowAnyException();
        assertThatCode(() -> storage.delete(identifier)).doesNotThrowAnyException();
    }

    @Test
    void deletingOneFileLeavesTheOthersAlone() {
        StorageService storage = storageAt(tempDir);
        StorageIdentifier removed = storage.store(CONTENT);
        StorageIdentifier kept = storage.store("another".getBytes(StandardCharsets.UTF_8));

        storage.delete(removed);

        assertThat(tempDir.resolve(kept.value())).isRegularFile();
    }

    @Test
    void refusesToStoreNullContent() {
        StorageService storage = storageAt(tempDir);

        assertThatThrownBy(() -> storage.store(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void refusesToDeleteWithoutAnIdentifier() {
        StorageService storage = storageAt(tempDir);

        assertThatThrownBy(() -> storage.delete(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * A misconfigured storage root must stop the application, not surface later as a failed
     * upload on the first student who tries one.
     */
    @Test
    void refusesToStartWithoutAConfiguredRoot() {
        assertThatThrownBy(() -> new StorageService(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage root is not configured");

        assertThatThrownBy(() -> new StorageService(new StorageProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage root is not configured");
    }

    private static StorageService storageAt(Path root) {
        return new StorageService(new StorageProperties(root));
    }

    private static List<String> filesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
