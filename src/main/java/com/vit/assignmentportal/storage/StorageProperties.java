package com.vit.assignmentportal.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The storage capability's own configuration, bound from {@code app.storage.*}.
 *
 * <h2>Why this is here and not in {@code config}</h2>
 *
 * <p>docs/architecture/application-structure.md originally placed this class in {@code config},
 * under the worked example "Bind {@code UPLOAD_STORAGE_PATH} to a typed bean". That placement
 * contradicts the rule in the same document: {@code config} is the outermost ring and features
 * and capabilities must never depend on it. {@link StorageService} needs this type, so a copy in
 * {@code config} would make {@code storage → config} — exactly the edge the dependency rules
 * exist to forbid.
 *
 * <p>Configuration <em>binding</em> is cross-cutting; the <em>shape</em> of a capability's
 * settings belongs to the capability. That is the distinction that resolves the conflict, and
 * the document now says so.
 *
 * <p>Registered by {@code @ConfigurationPropertiesScan} on {@code AssignmentPortalApplication},
 * so no {@code @EnableConfigurationProperties} is needed and the class stays free of Spring
 * configuration concerns.
 *
 * @param root the directory uploaded artefacts are written to. Bound from
 *             {@code UPLOAD_STORAGE_PATH}, defaulting to {@code ./uploads}. There is no secret
 *             here — the value is a path, not a credential — so it is committed as a default
 *             and overridden per environment (ADR-004, {@code CLAUDE.md} §8).
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(Path root) {
}
