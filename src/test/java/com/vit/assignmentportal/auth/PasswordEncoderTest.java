package com.vit.assignmentportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password encoding, proved at the lowest layer that can prove it.
 *
 * <p>No Spring context and no database. The behaviour under test belongs entirely to
 * {@link BCryptPasswordEncoder}, and starting a container to observe it would make a
 * millisecond assertion take seconds — the pyramid rule in docs/testing/strategy.md §2.
 *
 * <p>What this class does <em>not</em> prove is that the application uses this encoder. That is
 * a wiring question, and it is answered by {@code AuthenticationIT}, which signs in against the
 * real configuration and a real database.
 */
class PasswordEncoderTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * The whole point: the stored value is not the password.
     *
     * <p>Stated as an assertion rather than left implicit in the encoder's implementation,
     * because this is the property that makes a stolen table survivable
     * ({@code SECURITY.md}).
     */
    @Test
    void neverStoresThePlaintextPassword() {
        String encoded = encoder.encode(PASSWORD);

        assertThat(encoded).isNotEqualTo(PASSWORD);
        assertThat(encoded).doesNotContain(PASSWORD);
    }

    @Test
    void verifiesThePasswordItEncoded() {
        assertThat(encoder.matches(PASSWORD, encoder.encode(PASSWORD))).isTrue();
    }

    @Test
    void rejectsADifferentPassword() {
        String encoded = encoder.encode(PASSWORD);

        assertThat(encoder.matches("correct-horse-battery-stapl", encoded)).isFalse();
        assertThat(encoder.matches("", encoded)).isFalse();
        assertThat(encoder.matches("CORRECT-HORSE-BATTERY-STAPLE", encoded)).isFalse();
    }

    /**
     * The salt is per-hash, so the same password encodes to a different value every time.
     *
     * <p>This is what makes a rainbow table useless against the stored column: an attacker
     * cannot precompute a hash once and match every user who chose that password, and cannot
     * tell from the table that two users share one.
     *
     * <p>It is also why a password cannot be "looked up" by its hash, and therefore why
     * authentication must be a verification rather than a comparison.
     */
    @Test
    void producesADifferentHashForTheSamePasswordEachTime() {
        String first = encoder.encode(PASSWORD);
        String second = encoder.encode(PASSWORD);

        assertThat(first).isNotEqualTo(second);
        // Both still verify. A salted hash is different, not wrong.
        assertThat(encoder.matches(PASSWORD, first)).isTrue();
        assertThat(encoder.matches(PASSWORD, second)).isTrue();
    }

    /**
     * Records the shape of the stored value, which is the contract
     * {@code V1__baseline.sql} declares as {@code VARCHAR(100)}.
     *
     * <p>BCrypt emits exactly 60 characters, beginning with the algorithm and cost markers
     * ({@code $2a$10$}). The column is wider on purpose — sizing a column to precisely today's
     * algorithm makes an encoder change into a migration — so this test pins the lower bound
     * rather than the exact width.
     */
    @Test
    void emitsAValueThePasswordHashColumnCanHold() {
        String encoded = encoder.encode(PASSWORD);

        assertThat(encoded).hasSize(60);
        assertThat(encoded).startsWith("$2");
        // The migration declares VARCHAR(100); a 60-character value fits with room to spare.
        assertThat(encoded.length()).isLessThanOrEqualTo(100);
    }
}
