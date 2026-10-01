package com.vit.assignmentportal.storage;

/**
 * A file-system operation could not be completed.
 *
 * <p>It exists so that a failure to write, read, or remove an artefact is a named condition a
 * caller can catch, rather than a bare {@code UncheckedIOException} indistinguishable from any
 * other I/O problem. The message is written for an operator reading a log, and is never rendered
 * to a user: {@code server.error.include-message} is {@code never} in {@code application.yml},
 * and no template displays an exception message ({@code CLAUDE.md} §7 rule 11).
 *
 * <p>Deliberately unchecked. A storage failure is not something a caller can meaningfully
 * recover from mid-request — the upload cannot complete — and forcing a checked exception
 * through the service boundary would only produce empty {@code catch} blocks.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
