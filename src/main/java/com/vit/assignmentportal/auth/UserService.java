package com.vit.assignmentportal.auth;

import com.vit.assignmentportal.common.Role;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The only way a user is created, found, or has a password set.
 *
 * <p>Every other part of the application goes through here rather than through
 * {@link UserRepository}, which is what keeps password encoding in one place. If the encoder ever
 * changes, it changes here and the repository is unaffected.
 *
 * <p><strong>Note what is injected.</strong> A {@code PasswordEncoder} is a Spring Security type,
 * wired as a bean in {@code config.SecurityConfiguration}. This class imports it from
 * {@code org.springframework.security}, never from {@code com.vit.assignmentportal.config} —
 * features may consume a bean whose type belongs to a library, but they may not import the
 * configuration package that happens to declare it (docs/architecture/application-structure.md
 * §4 rule 3).
 */
@Service
public class UserService {

    /**
     * An address longer than this cannot be a valid RFC 5321 path, and the column that stores it
     * is {@code VARCHAR(254)}. Rejecting it here produces a message; letting MySQL reject it
     * produces a constraint violation.
     */
    private static final int MAX_EMAIL_LENGTH = 254;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user with an encoded password.
     *
     * @param email       the login identifier; surrounding whitespace is ignored
     * @param rawPassword the password as supplied; encoded here and never stored as given
     * @param role        the single MVP role
     * @throws IllegalArgumentException if any argument is missing or malformed, or if the email is
     *                                  already registered
     */
    @Transactional
    public User create(String email, String rawPassword, Role role) {
        String normalizedEmail = normalizeEmail(email);

        if (!StringUtils.hasText(rawPassword)) {
            // A blank password is the difference between "this account is seeded" and "this
            // account is open to anyone". It is refused here rather than at the encoder, so the
            // failure names the actual problem.
            throw new IllegalArgumentException("A password is required to create a user");
        }
        if (role == null) {
            throw new IllegalArgumentException("A role is required to create a user");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("A user with this email address already exists");
        }

        return userRepository.save(
                User.register(normalizedEmail, passwordEncoder.encode(rawPassword), role));
    }

    /**
     * Finds a user by login identifier, matching case-insensitively as the column collation
     * does.
     */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return StringUtils.hasText(email)
                ? userRepository.findByEmail(normalizeEmail(email))
                : Optional.empty();
    }

    /** Answers whether an email is already registered, without loading the row. */
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return StringUtils.hasText(email) && userRepository.existsByEmail(normalizeEmail(email));
    }

    /**
     * Trims only. The address is stored with the capitalisation the user chose, and matched
     * case-insensitively by the database — lower-casing it here would destroy a display detail to
     * solve a problem the collation has already solved.
     */
    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("An email address is required");
        }
        String trimmed = email.trim();
        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "An email address may be at most " + MAX_EMAIL_LENGTH + " characters");
        }
        return trimmed;
    }
}
