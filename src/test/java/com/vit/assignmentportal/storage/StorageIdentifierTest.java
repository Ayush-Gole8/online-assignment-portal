package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.support.TestCategories;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The invariant that makes path traversal unrepresentable ({@code CLAUDE.md} §7 rule 5).
 *
 * <p>The refusal list is the point of this class. Each entry is a payload a client could put in
 * {@code Content-Disposition} or in a multipart filename, and each must fail at construction —
 * before {@link StorageService} is ever asked to resolve a path. If one of these ever starts
 * passing, the containment check in {@code StorageService.resolve} is the only thing left standing
 * between an upload and the filesystem.
 */
@Tag(TestCategories.UNIT)
class StorageIdentifierTest {

    @Test
    void generatesACanonicalLowercaseUuid() {
        String value = StorageIdentifier.generate().value();

        assertThat(value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        // Round-tripping through UUID is the strongest statement that the form is canonical: it
        // fails for anything UUID.toString() would not have produced.
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    @Test
    void generatesADifferentIdentifierEachTime() {
        assertThat(StorageIdentifier.generate().value())
                .isNotEqualTo(StorageIdentifier.generate().value());
    }

    @Test
    void acceptsAnExplicitlyConstructedUuid() {
        String value = UUID.randomUUID().toString();

        assertThat(new StorageIdentifier(value).value()).isEqualTo(value);
    }

    @Test
    void refusesAParentDirectoryTraversal() {
        assertRefused("../../etc/passwd");
        assertRefused("..");
        assertRefused("./..");
    }

    @Test
    void refusesAnAbsolutePath() {
        assertRefused("/etc/passwd");
        assertRefused("C:\\Windows\\win.ini");
        assertRefused("\\\\server\\share\\file");
    }

    @Test
    void refusesASubdirectoryEvenUnderTheUuidPrefix() {
        // A payload that begins with a perfectly good identifier and then leaves the directory.
        // A check that only looked at the first 36 characters would accept this one.
        assertRefused(UUID.randomUUID() + "/../../etc/passwd");
        assertRefused(UUID.randomUUID() + ".pdf.bak");
    }

    @Test
    void refusesAUuidInTheWrongCase() {
        // A fixed value, not a generated one: a random UUID occasionally contains only digits, and
        // upper-casing those would produce a value the type should accept. The test would then pass
        // without asserting anything.
        assertRefused("ABCDEF12-3456-7890-ABCD-EF1234567890");
    }

    @Test
    void refusesAUuidWithSurroundingWhitespaceOrNewlines() {
        String value = UUID.randomUUID().toString();

        assertRefused(" " + value);
        assertRefused(value + " ");
        // A trailing newline is the classic way to slip past a regex that was not anchored.
        assertRefused(value + "\n");
        assertRefused(value + "\r\n");
    }

    @Test
    void refusesAnEmptyOrAbsentValue() {
        assertRefused("");
        assertRefused(null);
    }

    @Test
    void refusesAValueThatIsNotUuidShaped() {
        assertRefused("not-a-uuid");
        assertRefused("1234567890123456789012345678901234567890");
    }

    @Test
    void refusesAValidationBypassAttemptThroughUnicode() {
        // A homoglyph or a non-ASCII digit would normalise under some filesystems.
        assertRefused("١٢٣٤٥٦٧٨-1234-1234-1234-123456789012");
    }

    private static void assertRefused(String value) {
        assertThatThrownBy(() -> new StorageIdentifier(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase UUID");
    }
}
