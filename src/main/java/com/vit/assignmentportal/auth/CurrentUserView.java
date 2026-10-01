package com.vit.assignmentportal.auth;

import com.vit.assignmentportal.common.Role;

/**
 * The view model for the signed-in user — what a template is allowed to render.
 *
 * <p>It exists because {@link User} is a persistence entity and a template is not a repository.
 * Handing the entity to Thymeleaf would put the password hash one typo — {@code
 * ${currentUser.passwordHash}} — away from an HTML page, and would let a view's requirements
 * start shaping the database mapping. A record with three fields cannot leak a credential,
 * because it does not hold one.
 *
 * <p>The fields are chosen for what the page shows: the email to greet the user by, and the role
 * so that the page can say what they may do. {@code id} and the timestamps are deliberately
 * absent — a view that does not display them has no reason to receive them, and {@code id} in
 * particular is the value a feature will scope its own queries by, server-side, from
 * {@code CurrentUserService} rather than from anything a page carries.
 *
 * @param email the signed-in user's login identifier
 * @param role  the single MVP role, which drives what the page offers
 */
public record CurrentUserView(String email, Role role) {

    /**
     * Adapts the authenticated principal to a view model.
     *
     * <p>A static factory rather than a constructor call in the controller: the mapping from
     * principal to view model is this record's business, and keeping it here means there is one
     * place to change when the page needs a different field.
     */
    public static CurrentUserView from(AuthenticatedUser user) {
        return new CurrentUserView(user.email(), user.role());
    }
}
