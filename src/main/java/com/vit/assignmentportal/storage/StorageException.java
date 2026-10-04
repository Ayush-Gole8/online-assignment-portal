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
 * <p><strong>It deliberately does not extend {@code PortalException}.</strong> That base means
 * "this application refused a request, and the message is safe to render" — and this message is
 * neither. It names a storage identifier and exists so an operator can reconcile the volume
 * against the table; it is diagnostics, not a sentence for a student. Keeping it outside the
 * hierarchy is what lets the web layer's handler render a {@code PortalException}'s message and
 * refuse, structurally, to render this one. It is a {@code RuntimeException} because a storage
 * failure is not something a caller can meaningfully recover from mid-request — the upload cannot
 * complete — and forcing a checked exception through the service boundary would only produce empty
 * {@code catch} blocks.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
