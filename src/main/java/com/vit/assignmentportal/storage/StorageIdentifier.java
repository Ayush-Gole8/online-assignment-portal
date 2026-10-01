package com.vit.assignmentportal.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The server-generated name of a stored file.
 *
 * <p>This type exists to make one class of vulnerability <strong>unrepresentable</strong> rather
 * than merely guarded against. A storage identifier is generated server-side from a
 * {@link UUID}; the uploaded file's original name is never part of it
 * ({@code CLAUDE.md} §7 rule 5). Because the compact constructor rejects anything that is not a
 * bare lowercase UUID, a value such as {@code ../../etc/passwd} cannot be constructed at all —
 * so no caller, now or later, can hand {@link StorageService} a path-traversal payload, and
 * {@code StorageService} does not have to remember to check.
 *
 * <p>The alternative — a {@code String} identifier validated at each use — puts the guarantee in
 * the weakest place: every future call site. Encoding the invariant in the type puts it where it
 * cannot be forgotten.
 *
 * <p>Stored as the bare UUID, with no file extension. The extension would claim a format, and
 * the format is already recorded properly in the {@code content_type} column; a file named
 * {@code .pdf} that is not one is the kind of thing that misleads whoever inspects the volume at
 * three in the morning. Operators map a file back to its row through the
 * {@code submissions.stored_file_name} column.
 *
 * @param value the identifier; a lowercase RFC 4122 UUID in its canonical hyphenated form
 */
public record StorageIdentifier(String value) {

    /**
     * The canonical form of {@link UUID#toString()} — five hexadecimal groups separated by
     * hyphens, lowercase throughout.
     *
     * <p>Lowercase is not cosmetic: it is what {@code UUID.randomUUID()} produces, and pinning
     * it means two identifiers that differ only in case cannot name two different files.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public StorageIdentifier {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "A storage identifier must be a lowercase UUID in canonical form.");
        }
    }

    /**
     * Generates a new identifier.
     *
     * <p>{@link UUID#randomUUID()} is used rather than a sequence: the identifier is visible in a
     * filesystem listing, and a predictable one would let anyone who can list the volume
     * enumerate the artefacts — and correlate them with the order they were uploaded in.
     */
    public static StorageIdentifier generate() {
        return new StorageIdentifier(UUID.randomUUID().toString());
    }
}
