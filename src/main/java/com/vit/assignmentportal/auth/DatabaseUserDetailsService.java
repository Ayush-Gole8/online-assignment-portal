package com.vit.assignmentportal.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bridge from Spring Security to this application's users: the adapter that lets the
 * framework's authentication machinery work against the {@code users} table without knowing
 * anything about it.
 *
 * <p>Everything security-related about this class is inherited rather than written. Password
 * verification, the constant-time comparison, and the decision to refuse a disabled account all
 * belong to {@code DaoAuthenticationProvider} and the {@code BCryptPasswordEncoder} it is given.
 * This class answers one question — "what is the account behind this identifier?" — and returns
 * an object the provider can check.
 *
 * <p>This is also the point at which the {@link User} entity stops: what leaves this method is an
 * {@link AuthenticatedUser}, which carries an id, an email, a role, and a hash, and nothing else.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads the account behind a login identifier.
     *
     * <p>The exception message names the email that was tried. That is safe here and only here:
     * {@code DaoAuthenticationProvider} hides {@code UsernameNotFoundException} behind a generic
     * {@code BadCredentialsException} before it reaches the browser, so a caller cannot use the
     * sign-in form to discover which addresses are registered. Writing the same distinction into
     * the <em>response</em> would undo that, and the login page does not.
     *
     * <p>{@code readOnly} is not decoration. This runs on every sign-in and on every request that
     * re-reads the principal, and it performs a single indexed lookup that must not open a
     * writable transaction.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No account is registered for " + email));

        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getRole(),
                user.isEnabled());
    }
}
