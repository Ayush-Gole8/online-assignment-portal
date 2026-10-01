package com.vit.assignmentportal.auth;

import com.vit.assignmentportal.common.Role;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal: what the rest of the application is allowed to know about the
 * signed-in user.
 *
 * <p>This is the boundary that keeps {@link User} inside the {@code auth} feature. It carries an
 * id, an email, a role, and the credential Spring Security needs to verify a password — and
 * nothing else. No timestamps, no enabled-flag mutators, no repository hooks.
 *
 * <p>An immutable record on purpose. A principal is a snapshot taken at authentication time, so
 * mutating one would change what the current request believes without changing what the database
 * says.
 *
 * @param id           the user's identifier, used by features to scope their own queries
 * @param email        the login identifier, and Spring Security's notion of a username
 * @param passwordHash the encoded credential, read by the {@code DaoAuthenticationProvider}
 *                     during verification and by nothing else afterwards
 * @param role         the single MVP role
 * @param enabled      {@code false} for a disabled account, which Spring Security refuses
 * @see CurrentUserService the supported way for a feature to obtain this
 */
public record AuthenticatedUser(
        UUID id,
        String email,
        String passwordHash,
        Role role,
        boolean enabled
) implements UserDetails {

    /**
     * The single authority of this principal, derived from its role.
     *
     * <p>Exactly one, because the MVP grants exactly one role per user (data-model.md §2). The
     * route rules match on this; a mismatch between it and {@code hasRole(...)} in
     * {@code SecurityConfiguration} denies every gated route, which is why the integration tests
     * exercise the routes rather than this method.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /** Spring Security's name for the credential. Not the hash's name — that is deliberate. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Spring Security's name for the login identifier, which for this application is the email. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // The MVP has no account-expiry, locking, or credential-expiry policy. These return true
    // rather than being omitted so that the absence of a policy is a stated decision in the code,
    // not an inherited default nobody has read.

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Never renders {@code passwordHash}.
     *
     * <p>A record's generated {@code toString} prints every component, and this object ends up in
     * Spring Security's debug logging, in a debugger, and in a stack trace. The generated version
     * would write a BCrypt hash into a log file the first time anyone set that logger to DEBUG —
     * exactly the accident {@code CLAUDE.md} §8 forbids.
     */
    @Override
    public String toString() {
        return "AuthenticatedUser[id=%s, email=%s, role=%s, enabled=%s]"
                .formatted(id, email, role, enabled);
    }
}
