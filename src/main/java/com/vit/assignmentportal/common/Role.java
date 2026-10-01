package com.vit.assignmentportal.common;

/**
 * The three roles of the MVP, and the only three that exist ({@code CLAUDE.md} §7 rule 9).
 *
 * <p>A role is never inferred from a request parameter, a header, or a form field. It is read
 * from the authenticated principal, which was itself loaded from the database by
 * {@code auth.DatabaseUserDetailsService}. That one-way path is what makes the role
 * trustworthy.
 *
 * <p>There is deliberately no {@code getAuthority()} returning Spring Security's
 * {@code GrantedAuthority}: this is the shared kernel, and it stays free of framework types
 * (docs/architecture/application-structure.md §4 rule 4). {@link #authority()} returns the
 * conventional string, and the security adapter in {@code auth} is what turns it into something
 * Spring Security understands.
 */
public enum Role {

    /** Submits coursework and reads only their own submissions. */
    STUDENT,

    /** Reviews submissions in the queue and records an approve/reject decision. */
    REVIEWER,

    /** Reserved for administration. The MVP ships no admin UI (see {@code mvp.md} §3). */
    ADMIN;

    /**
     * The authority name Spring Security's {@code hasRole(...)} expressions match against.
     *
     * <p>{@code hasRole("REVIEWER")} implicitly prefixes {@code ROLE_}, so the value returned
     * here already carries it. Dropping the prefix would silently deny every role-gated route,
     * which is why the security tests assert on the routes themselves rather than on this
     * method.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}
