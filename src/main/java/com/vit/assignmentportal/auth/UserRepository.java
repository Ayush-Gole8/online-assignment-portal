package com.vit.assignmentportal.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link User}. Owned by the {@code auth} feature — no other feature may import
 * it (docs/architecture/application-structure.md §4 rule 1).
 *
 * <p>There is no "find by role" or "list all": nothing in this milestone needs one. A repository
 * method is a query someone has to maintain, and speculative finders are how a repository
 * interface becomes a description of the table rather than of the application.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds the account behind a login identifier.
     *
     * <p>No {@code IgnoreCase} suffix. Case-insensitive matching is a property of the column —
     * its collation is {@code utf8mb4_0900_ai_ci} — and an {@code IgnoreCase} finder would add
     * {@code UPPER()} to the predicate, defeating the unique index on every sign-in. The database
     * already answers this question the way the application means it.
     */
    Optional<User> findByEmail(String email);

    /**
     * Answers "is this email already registered?" without loading the row.
     *
     * <p>Used by {@code DevelopmentUserSeeder} to stay idempotent and by
     * {@code UserService.create} to reject a duplicate before it becomes a constraint violation.
     */
    boolean existsByEmail(String email);
}
