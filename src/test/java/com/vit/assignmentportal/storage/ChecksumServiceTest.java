package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.vit.assignmentportal.support.TestCategories;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The digest, checked against published vectors rather than against itself.
 *
 * <p>Asserting that two calls agree would pass for any deterministic function — including one
 * that returned the input unchanged. The digests below are the FIPS 180-4 worked examples for
 * SHA-256, so a regression to a different algorithm, a different character encoding, or a
 * different hex case fails here and not in production three months later.
 */
@Tag(TestCategories.UNIT)
class ChecksumServiceTest {

    /** FIPS 180-4, the digest of the empty input. */
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** FIPS 180-4, the digest of the ASCII string "abc". */
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final ChecksumService service = new ChecksumService();

    @Test
    void matchesThePublishedDigestOfTheEmptyInput() {
        assertThat(service.sha256(new byte[0])).isEqualTo(SHA256_OF_EMPTY);
    }

    @Test
    void matchesThePublishedDigestOfAbc() {
        assertThat(service.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(SHA256_OF_ABC);
    }

    /**
     * The case that makes the digest useful: an artefact whose bytes were altered after upload no
     * longer matches the recorded value.
     */
    @Test
    void producesADifferentDigestForDifferentContent() {
        String first = service.sha256("essay v1".getBytes(StandardCharsets.UTF_8));
        String second = service.sha256("essay v2".getBytes(StandardCharsets.UTF_8));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void producesTheSameDigestForTheSameContent() {
        byte[] content = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

        assertThat(service.sha256(content)).isEqualTo(service.sha256(content.clone()));
    }

    /**
     * The column is {@code CHAR(64)} and the value is compared as a plain string, so the width and
     * the case are part of the contract, not a formatting detail.
     */
    @Test
    void alwaysProducesSixtyFourLowercaseHexCharacters() {
        byte[] content = "anything at all".getBytes(StandardCharsets.UTF_8);

        assertThat(service.sha256(content)).hasSize(64).matches("[0-9a-f]{64}");
    }

    /**
     * An independent path to the same digest: {@code MessageDigest} fed in two chunks rather than
     * in the single call the service makes. A digest is defined over the whole input regardless of
     * how it is delivered, so a service that hashed only the first buffer would agree with a
     * one-call comparison and disagree with this one.
     */
    @Test
    void agreesWithAnIndependentlyComputedDigest() throws Exception {
        byte[] content = new byte[1024 * 1024 + 17];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index % 251);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(content, 0, 700_000);
        digest.update(content, 700_000, content.length - 700_000);

        assertThat(service.sha256(content))
                .isEqualTo(HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void refusesNullContent() {
        assertThatThrownBy(() -> service.sha256(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content is required");
    }

    /**
     * Guards the assumption the service's Javadoc makes when it treats
     * {@code NoSuchAlgorithmException} as unreachable. If SHA-256 ever stopped being available,
     * that catch block would become live code, and the failure would arrive as a startup surprise
     * rather than as a compiler error.
     */
    @Test
    void sha256IsAvailableOnThisPlatform() {
        assertDoesNotThrow(() -> MessageDigest.getInstance("SHA-256"));
    }
}
