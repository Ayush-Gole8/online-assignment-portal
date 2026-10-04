package com.vit.assignmentportal.common;

/**
 * The base of the application's own failure vocabulary: a condition this application refused, whose
 * message is <strong>safe to render verbatim</strong>.
 *
 * <h2>What membership means</h2>
 *
 * <p>A subclass asserts two things about itself and every message it is thrown with:
 *
 * <ol>
 *   <li><strong>It was raised by this application, not by a framework.</strong> A stack overflow, a
 *       null dereference and a {@code DataIntegrityViolationException} are not members, and are
 *       handled by the framework's error path. This type is for the conditions where the application
 *       had something to say.</li>
 *   <li><strong>The message is a sentence written for a person.</strong> It is composed here, it
 *       never contains text the client supplied, and it never describes a SQL statement, a
 *       filesystem path, an exception class, or a configuration value
 *       ({@code CLAUDE.md} §7 rule 11). That is what lets a single handler render it without
 *       inspecting it.</li>
 * </ol>
 *
 * <p>It is deliberately <strong>not</strong> the base of everything that can go wrong — merely of
 * everything the application means to say out loud. The distinction is load-bearing: it is why
 * {@code storage.StorageException}, whose message names a storage identifier for an operator, is a
 * {@code RuntimeException} and not one of these. A handler may echo a {@code PortalException}'s
 * message and must never echo a {@code StorageException}'s.
 *
 * <h2>Why the base is here, in the shared kernel</h2>
 *
 * <p>Its subclasses live in the features that own them — {@code InvalidSubmissionException} in
 * {@code submission}, {@code InvalidReviewException} in {@code review} — and two features may not
 * import one another. The base therefore has to sit below both, which is what the shared kernel is
 * for (docs/architecture/application-structure.md §4 rule 4).
 *
 * <p>It carries no HTTP status. {@code common} holds value types and domain exceptions and stays
 * free of framework types ({@code Role}'s javadoc makes the same point about
 * {@code GrantedAuthority}), so the translation from a failure to a response belongs to the web
 * layer, in {@code config} — see {@code PortalExceptionHandler}.
 */
public abstract class PortalException extends RuntimeException {

    /**
     * @param message the sentence to render to the user, safe verbatim — see the class contract
     */
    protected PortalException(String message) {
        super(message);
    }
}
