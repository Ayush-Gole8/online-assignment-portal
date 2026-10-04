package com.vit.assignmentportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.support.TestCategories;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The security decision that turns a database row into a principal.
 *
 * <p>This is where the application's identity boundary is drawn: the {@link User} entity, which
 * carries a password hash and two timestamps, stops here, and an {@link AuthenticatedUser} — an id,
 * an email, a role, and the flag Spring Security checks — goes on. What the rest of the application
 * believes about a signed-in user was decided by the two methods asserted here.
 *
 * <p>A unit test rather than an integration test because the question is a mapping and not a query.
 * {@code AuthenticationIT} already proves the wiring — that the real filter chain reaches this class
 * against a real database. What it cannot state cheaply is the shape of what comes back: that the
 * authority is exactly one, that it carries the {@code ROLE_} prefix {@code hasRole(...)} matches,
 * and that a disabled account is handed on <em>as disabled</em> rather than refused here. That
 * refusal is deliberate and belongs to {@code DaoAuthenticationProvider}; a service that pre-empted
 * it would be a second place that knows which accounts may sign in.
 */
@Tag(TestCategories.UNIT)
class DatabaseUserDetailsServiceTest {

    private static final String HASH = "$2a$10$notarealhashbutshapedlikeone0000000000000000000000000000000";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DatabaseUserDetailsService service =
            new DatabaseUserDetailsService(userRepository);

    /**
     * The principal carries the account's identity and nothing that could leak.
     *
     * <p>The email, the role, and the hash are the values a later verification step needs; the id is
     * what a feature scopes its queries by. The hash is present because Spring Security's
     * {@code DaoAuthenticationProvider} compares against it — and is the reason {@link
     * AuthenticatedUser#toString()} omits it, which is a different test's concern.
     */
    @Test
    void returnsAPrincipalCarryingTheAccountIdentity() {
        User account = account("student@example.com", Role.STUDENT, true);
        when(userRepository.findByEmail("student@example.com")).thenReturn(Optional.of(account));

        AuthenticatedUser principal =
                (AuthenticatedUser) service.loadUserByUsername("student@example.com");

        assertThat(principal.id()).isEqualTo(account.getId());
        assertThat(principal.email()).isEqualTo("student@example.com");
        assertThat(principal.role()).isEqualTo(Role.STUDENT);
        assertThat(principal.getPassword()).isEqualTo(account.getPasswordHash());
        assertThat(principal.isEnabled()).isTrue();
    }

    /**
     * The principal has exactly one authority, named for its role with the prefix the route rules
     * match.
     *
     * <p>Asserted against the literal {@code "ROLE_" + role} rather than against
     * {@code role.authority()}, because comparing a value to a method that computes it would assert
     * only that the method is deterministic. The prefix is not decoration: {@code hasRole("REVIEWER")}
     * expands to {@code ROLE_REVIEWER}, so an authority missing it would deny every gated route —
     * silently, since a denied route looks the same as a route that was never reached.
     */
    @Test
    void givesThePrincipalOneAuthorityNamedForItsRole() {
        for (Role role : Role.values()) {
            User account = account(role.name().toLowerCase() + "@example.com", role, true);
            when(userRepository.findByEmail(account.getEmail())).thenReturn(Optional.of(account));

            AuthenticatedUser principal =
                    (AuthenticatedUser) service.loadUserByUsername(account.getEmail());

            assertThat(principal.getAuthorities()).hasSize(1);
            assertThat(principal.getAuthorities().iterator().next().getAuthority())
                    .isEqualTo("ROLE_" + role.name());
        }
    }

    /**
     * A disabled account is reported as disabled, not refused here.
     *
     * <p>The distinction matters: refusing it in this method would put the decision in two places,
     * and the second copy is the one that would be forgotten. Handing back an enabled flag of
     * {@code false} lets the provider make the one decision, which is also what makes revocation
     * effective on the user's next request (ADR-005).
     */
    @Test
    void reportsADisabledAccountAsDisabled() {
        User account = account("disabled@example.com", Role.STUDENT, false);
        when(userRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(account));

        AuthenticatedUser principal =
                (AuthenticatedUser) service.loadUserByUsername("disabled@example.com");

        assertThat(principal.isEnabled()).isFalse();
    }

    /**
     * An address nobody holds is refused, and the lookup used the address it was given.
     *
     * <p>The exception names the email it tried. That is safe only because the provider hides it
     * behind a generic {@code BadCredentialsException} before the browser sees it; the response-level
     * proof of that is {@code AuthenticationIT}, which asserts an unknown email and a wrong password
     * land on the same page.
     */
    @Test
    void refusesAnUnknownEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);

        verify(userRepository).findByEmail("nobody@example.com");
    }

    /** A persisted account with the shape the mapping reads: an identifier, a hash, a role, a flag. */
    private static User account(String email, Role role, boolean enabled) {
        User user = User.register(email, HASH, role);
        // Assigns the identifier the way persistence would, so the principal is built from a row that
        // looks like one the database produced rather than one with a null id.
        user.onInsert();
        if (!enabled) {
            user.setEnabled(false);
        }
        return user;
    }
}
