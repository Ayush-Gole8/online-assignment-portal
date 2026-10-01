package com.vit.assignmentportal.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Computes the SHA-256 digest of an uploaded artefact.
 *
 * <p>Named for what it does rather than for its category — this is the class that would have
 * been called {@code FileUtils} in a project without the naming rules in {@code CLAUDE.md} §5,
 * and that name is exactly what turns a class into a dumping ground.
 *
 * <p>The digest is recorded on every submission so that an artefact can be shown to be the one
 * that was uploaded, months later, without trusting the file name or the timestamp. It is also
 * the tie-breaker if a stored file and its database row are ever suspected of disagreeing
 * (docs/architecture/data-model.md §6).
 *
 * <p>This is <strong>not</strong> the anti-malware control, and it must not be mistaken for one.
 * A checksum proves the bytes have not changed; it says nothing about whether they were ever
 * safe, because an attacker-supplied file has a perfectly valid checksum of its own. The
 * controls that gate what may be stored at all are in {@link FileValidationService}.
 */
@Service
public class ChecksumService {

    /**
     * The algorithm is named in the FIPS 180-4 standard and is required of every Java platform,
     * so the {@code NoSuchAlgorithmException} below is unreachable in practice. It is handled
     * rather than declared because a missing SHA-256 would mean a broken JRE, and that should
     * surface as a clear failure at the call site instead of a checked exception on every
     * signature in the application.
     */
    private static final String ALGORITHM = "SHA-256";

    /**
     * The digest of the given bytes, as 64 lowercase hexadecimal characters.
     *
     * <p>Lowercase hex is not a preference: the column is {@code CHAR(64)} with a
     * case-insensitive collation, so a mixed-case digest would still match on a lookup but read
     * inconsistently in a dump. Fixing the case at the source keeps every stored digest
     * comparable as a plain string.
     *
     * @param content the bytes to digest; must not be {@code null}
     * @return the hexadecimal SHA-256 digest
     */
    public String sha256(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("Content is required to compute a checksum");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException(
                    ALGORITHM + " is required of every Java platform but was not available", cause);
        }
    }
}
