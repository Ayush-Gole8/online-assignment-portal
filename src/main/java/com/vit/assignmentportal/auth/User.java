package com.vit.assignmentportal.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.common.UuidToVarcharConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A portal user: the identity behind every request (data-model.md §2).
 *
 * <p>Maps the {@code users} table created by {@code V1__baseline.sql}. Hibernate validates this
    @JdbcTypeCode(SqlTypes.VARCHAR)
 * (ADR-003).
 *
 * <p><strong>This entity does not leave the {@code auth} feature.</strong> Controllers hand
 * records to templates; the security layer converts it to an {@code AuthenticatedUser}. The
 * password hash is a field here and nowhere else.
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * Assigned by the application, not by the database — see {@link #onInsert()}.
     *
     * <p>No {@code @GeneratedValue}: the identifier is set before the insert rather than read
     * back after it, so a saved user always has an id and there is no window in which the entity
     * is persisted but unidentifiable.
     *
     * <p>{@link UuidToVarcharConverter} makes the column type the migration's decision rather
     * than the ORM's. Hibernate's own representation of {@code java.util.UUID} has changed
     * between major versions, and with {@code ddl-auto: validate} (ADR-003) a mismatch between
     * its default and {@code VARCHAR(36)} is a startup failure.
     */
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    /**
     * The login identifier, and the only one. Uniqueness is case-insensitive because the column
     * collation is {@code utf8mb4_0900_ai_ci} — enforced by the database rather than by
     * lower-casing in Java, so it holds for any writer (data-model.md §2).
     */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /**
     * A BCrypt hash. Never a plaintext password, never a reversible encoding.
     *
     * <p>Read only by {@code DatabaseUserDetailsService}; it is never rendered, never logged, and
     * {@link #toString()} does not include it.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * Stored as a string, not an ordinal. A readable value survives a schema dump and cannot
     * silently change meaning if the enum is reordered (data-model.md §2).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    /**
     * A disabled user cannot authenticate. Checked when the user is loaded, so disabling an
     * account takes effect on that user's next request — the concrete benefit of server-side
     * session state over a self-contained token (ADR-005).
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not part of the application's API — use {@link #register}. */
    protected User() {
    }

    private User(String email, String passwordHash, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }

    /**
     * Creates a user that has not been persisted yet.
     *
     * <p>The password arrives already encoded. That is not a convenience: encoding is a policy
     * decision that needs the application's {@code PasswordEncoder}, and an entity that reaches
     * for a bean is an entity that cannot be constructed in a plain unit test.
     */
    public static User register(String email, String passwordHash, Role role) {
        return new User(email, passwordHash, role);
    }

    /**
     * Assigns the identifier and the timestamps on the way into the database.
     *
     * <p>Doing it here rather than in the constructor keeps the entity's id {@code null} until it
     * is persisted, which is what lets Spring Data recognise the instance as new and issue an
     * {@code INSERT} rather than guessing at a {@code MERGE}.
     */
    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Enables or disables the account. There is deliberately no setter for the password hash: a
     * credential change is a deliberate operation that belongs in {@code UserService}, where it
     * can re-encode and can be audited, rather than a field assignment any caller can make.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Identity is the identifier. Two {@code User} instances are the same user when they hold the
     * same id, regardless of whether either has been mutated.
     *
     * <p>Transient instances fall back to reference equality rather than comparing a pair of
     * nulls — otherwise every unsaved user would equal every other unsaved user, which turns a
     * {@code Set} of pending users into a single element.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user) || id == null) {
            return false;
        }
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Deliberately omits the password hash and the timestamps. This object reaches log files and
     * debugger views, and a credential that is never rendered cannot be rendered by accident
     * ({@code CLAUDE.md} §8).
     */
    @Override
    public String toString() {
        return "User[id=%s, role=%s, enabled=%s]".formatted(id, role, enabled);
    }
}
