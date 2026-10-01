package com.vit.assignmentportal.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.UUID;

/**
 * Maps a {@link UUID} entity attribute to the {@code VARCHAR(36)} column that stores it.
 *
 * <p>This exists because of a specific trap. Hibernate maps {@code java.util.UUID} to a
 * database-specific type, and that choice has changed between major versions — MySQL has no
 * native UUID type, so Hibernate has represented it as {@code BINARY(16)} in some releases and
 * as a character column in others. This application runs with {@code ddl-auto: validate}
 * (ADR-003), so a mismatch between that default and the migrated column is a startup failure on
 * somebody else's machine.
 *
 * <p>An explicit converter removes the question. Hibernate sees a {@code String} attribute
 * mapped to {@code varchar}, which is exactly what the migrations declare, so the column
 * contract belongs to the migration rather than to the ORM's release notes.
 *
 * <h2>Not usable on an identifier</h2>
 *
 * <p>Hibernate 7 rejects an {@code AttributeConverter} on an {@code @Id} attribute:
 *
 * <pre>
 * org.hibernate.AnnotationException: 'AttributeConverter' not allowed for attribute 'id'
 *         annotated '@jakarta.persistence.Id'
 * </pre>
 *
 * <p>That is thrown while the entity manager factory is being built, so the failure is a context
 * that will not start rather than a mapping that misbehaves — and because Flyway's migrations are
 * ordered behind that factory, it takes the schema with it. Identifier attributes therefore pin
 * the same thing with {@code @JdbcTypeCode(SqlTypes.VARCHAR)} instead ({@code User.id},
 * {@code Submission.id}). This converter is for the ordinary {@code UUID} columns —
 * {@code Submission.studentId} and {@code Submission.reviewerId} — where it is still permitted.
 *
 * <p>Note the direction of the win: the schema is the source of truth and the mapping conforms
 * to it. The reverse — letting the ORM's default decide the schema — is how a project discovers
 * on deploy that its primary key column is a different type in production than it was in CI.
 *
 * <h2>Why this lives in {@code common} rather than in a feature</h2>
 *
 * <p>It was written for {@code auth/User} and moved here when {@code submission/Submission}
 * needed the same mapping for {@code student_id}. Leaving it in {@code auth} would have made
 * {@code submission} import a class from another feature, which the dependency rules forbid
 * except through a service — and this is not a service.
 *
 * <p>{@code common} is the shared kernel, which holds "value types, enums, and domain
 * exceptions" and deliberately nothing else (docs/architecture/application-structure.md §4
 * rule 4). This class qualifies on a narrow reading: it is the storage mapping <em>of a shared
 * value type</em>, {@code java.util.UUID}. It is a {@code @Converter} — a JPA annotation, not a
 * Spring bean — it is stateless, and it knows nothing about users, submissions, or any other
 * domain concept. A converter that did would belong in the feature that owns the concept.
 *
 * <p>Registered explicitly on each attribute rather than with {@code autoApply = true}. An
 * application-wide default would silently capture every future {@code UUID} attribute, including
 * one that genuinely wants a different representation; an explicit annotation is greppable from
 * the entity it affects.
 */
@Converter
public class UuidToVarcharConverter implements AttributeConverter<UUID, String> {

    @Override
    public String convertToDatabaseColumn(UUID attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public UUID convertToEntityAttribute(String columnValue) {
        // UUID.fromString throws IllegalArgumentException on a malformed value. That is the
        // correct outcome: a row whose identifier cannot be parsed is corruption, and it should
        // surface loudly rather than be papered over with a generated replacement.
        return columnValue == null ? null : UUID.fromString(columnValue);
    }
}
