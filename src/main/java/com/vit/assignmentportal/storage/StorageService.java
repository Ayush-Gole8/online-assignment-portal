package com.vit.assignmentportal.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps a {@link StorageIdentifier} to a location on the configured volume and performs the file
 * I/O ({@code CLAUDE.md} §5).
 *
 * <p>It knows nothing about submissions. It receives bytes and returns an identifier; it does not
 * know what the bytes are for, who uploaded them, or what happens if the caller changes its mind.
 * That is what lets the storage capability be reasoned about — and tested against a temporary
 * directory — without a database.
 *
 * <p>Bytes are written outside {@code src/main/resources/static}. Nothing in this class is
 * reachable by a URL: the volume is a directory on the host, not a servable path, so an artefact
 * cannot be fetched without going through an endpoint that checks who is asking
 * ({@code CLAUDE.md} §7 rule 7, ADR-004).
 *
 * <p><strong>{@link #store(byte[])} and {@link #delete(StorageIdentifier)} are the pair the
 * transaction relies on.</strong> They are the two halves of an operation that spans a file system
 * and a database, and neither can be made atomic with the other. {@code SubmissionService} is
 * where that gap is closed; read it before changing the contract here. {@link #load} is outside
 * that concern — it only reads.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /**
     * The prefix and suffix of the intermediate file {@link #store(byte[])} writes before moving
     * it into place. The suffix is not {@code .pdf}: a half-written file must not be mistakable
     * for a stored artefact by anything that lists the volume.
     */
    private static final String PARTIAL_FILE_PREFIX = "upload-";
    private static final String PARTIAL_FILE_SUFFIX = ".part";

    private final Path root;

    /**
     * @param properties the bound {@code app.storage} settings
     * @throws IllegalStateException if the root is not configured; a missing storage path must
     *                               stop the application rather than surface later as a failed
     *                               upload
     */
    public StorageService(StorageProperties properties) {
        if (properties == null || properties.root() == null) {
            throw new IllegalStateException(
                    "The upload storage root is not configured. Set app.storage.root, which "
                    + "application.yml binds to the UPLOAD_STORAGE_PATH environment variable.");
        }
        // Absolute and normalised once, at construction, so every later containment check
        // compares like with like. A relative root would be resolved against the working
        // directory at each call, and the process could in principle change it.
        this.root = properties.root().toAbsolutePath().normalize();
    }

    /**
     * Writes the bytes to the volume under a freshly generated identifier.
     *
     * <p>The write is not performed directly at the destination. It goes to a temporary file in
     * the same directory, which is then moved into place. A move within a directory is atomic on
     * every filesystem this application targets, so a crash, a full disk, or a killed container
     * leaves either no file at the identifier or the complete artefact — never a truncated one
     * that a later reader would happily checksum and find wanting. The temporary file is removed
     * on any failure.
     *
     * @param content the bytes to store; must not be {@code null}
     * @return the identifier the bytes were stored under
     * @throws StorageException if the bytes could not be written
     */
    public StorageIdentifier store(byte[] content) {
        Objects.requireNonNull(content, "content");

        StorageIdentifier identifier = StorageIdentifier.generate();
        Path partial = null;
        try {
            Files.createDirectories(root);
            partial = Files.createTempFile(root, PARTIAL_FILE_PREFIX, PARTIAL_FILE_SUFFIX);
            Files.write(partial, content);
            moveIntoPlace(partial, resolve(identifier));
            // The temporary file no longer exists under this path; clearing the reference stops
            // the finally block from trying to delete the file that is now the artefact.
            partial = null;
            return identifier;
        } catch (IOException cause) {
            throw new StorageException("The uploaded file could not be stored.", cause);
        } finally {
            discardPartialFile(partial);
        }
    }

    /**
     * Reads a stored file back.
     *
     * <p>Serves the download endpoints (FR-13, FR-17). Note what this method does <em>not</em> do:
     * decide whether the caller may have the bytes. It takes an identifier and returns the file,
     * exactly as {@link #store(byte[])} takes bytes and returns an identifier — the storage
     * capability has no idea who is asking, and authorisation is the submission feature's business
     * ({@code CLAUDE.md} §7 rule 8). A caller reaches this only after the ownership or role check
     * has already selected the row the identifier came from.
     *
     * <p>Returns a {@code byte[]} rather than a stream. The artefact is at most 10 MB — enforced by
     * the container, by {@code FileValidationService}, and by {@code ck_submissions_file_size} —
     * and the upload path already holds the whole file in memory to checksum it, so a stream here
     * would be a different trade-off made in only half the code. It is also what lets the caller
     * hash the bytes it is about to serve.
     *
     * <p>A missing file is a {@link StorageException}, not an empty result. A row exists that
     * names it, so its absence is not a normal condition — it means the volume and the table
     * disagree, which is exactly the state an operator must be told about rather than have papered
     * over with a zero-length download.
     *
     * @param identifier the file to read
     * @return the stored bytes
     * @throws StorageException if the file is missing or cannot be read
     */
    public byte[] load(StorageIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        try {
            return Files.readAllBytes(resolve(identifier));
        } catch (IOException cause) {
            // The identifier is named in the message because this is an operator-facing failure
            // about the volume's own consistency. It is never rendered to a user: the controller
            // answers a 500 through the error page, which includes no message (application.yml).
            throw new StorageException(
                    "The stored file " + identifier.value() + " could not be read.", cause);
        }
    }

    /**
     * Removes a stored file.
     *
     * <p><strong>Idempotent.</strong> Removing something that is not there is a success, not an
     * error. The caller is the rollback path in {@code SubmissionService}, which runs precisely
     * when something else has already gone wrong; a cleanup step that could fail because it was
     * invoked twice, or because the write itself never completed, would replace a handled failure
     * with an unhandled one.
     *
     * @param identifier the file to remove
     * @throws StorageException if the file exists and could not be removed
     */
    public void delete(StorageIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        try {
            Files.deleteIfExists(resolve(identifier));
        } catch (IOException cause) {
            throw new StorageException("The stored file could not be removed.", cause);
        }
    }

    /**
     * The absolute path of a stored file.
     *
     * <p>The containment check is unreachable — {@link StorageIdentifier} cannot hold a path
     * separator, so {@code resolve} can only ever produce a child of the root. It is kept because
     * it is what makes the guarantee enforced rather than merely asserted: if the identifier type
     * is ever loosened, this fails closed instead of writing outside the volume.
     */
    private Path resolve(StorageIdentifier identifier) {
        Path candidate = root.resolve(identifier.value()).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(
                    "A storage identifier must resolve to a file inside the storage root.");
        }
        return candidate;
    }

    private static void moveIntoPlace(Path partial, Path destination) throws IOException {
        try {
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            // A bind-mounted volume or a network share may not support an atomic rename. The
            // partial file is in the same directory as the destination, so the fallback is a
            // plain rename and cannot degenerate into a cross-device copy.
            Files.move(partial, destination);
        }
    }

    /**
     * Best-effort removal of the intermediate file.
     *
     * <p>Logs and continues. This runs while another failure is already being reported, and a
     * cleanup problem must not become the error the caller sees — the operator needs the original
     * cause, and a stray {@code .part} file is untidy rather than dangerous.
     */
    private static void discardPartialFile(Path partial) {
        if (partial == null) {
            return;
        }
        try {
            Files.deleteIfExists(partial);
        } catch (IOException cause) {
            log.warn("Could not remove the intermediate upload file {}. It can be deleted by hand.",
                    partial, cause);
        }
    }
}
