package com.vit.assignmentportal.auth;

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
 * mapped to {@code varchar}, which is exactly what {@code V1__baseline.sql} declares, so the
 * column contract belongs to the migration rather than to the ORM's release notes.
 *
 * <p>Note the direction of the win: the schema is the source of truth and the mapping conforms
 * to it. The reverse — letting the ORM's default decide the schema — is how a project discovers
 * on deploy that its primary key column is a different type in production than it was in CI.
 *
 * <p>Registered explicitly on the attribute it applies to rather than with
 * {@code autoApply = true}. {@code users} is the only table with a UUID key ({@code submissions}
 * uses a surrogate {@code BIGINT}, data-model.md §3), so there is nothing for a global default to
 * serve, and an explicit annotation is greppable from the entity.
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
