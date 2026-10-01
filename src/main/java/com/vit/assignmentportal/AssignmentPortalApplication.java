package com.vit.assignmentportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Git-Driven Online Assignment Submission Portal.
 *
 * <p>A single deployable modular monolith with embedded Tomcat (ADR-001, ADR-002). Feature
 * packages are added as the features themselves are built: {@code auth} arrived with the
 * authentication milestone, {@code storage} and {@code submission} with the student upload
 * slice. {@code review} is still to come.
 *
 * <p>Component scanning starts here and covers {@code com.vit.assignmentportal}.
 *
 * <p>{@link ConfigurationPropertiesScan} registers every {@code @ConfigurationProperties} class
 * under the base package, so a capability can declare its own settings — {@code
 * storage.StorageProperties} does — without an {@code @EnableConfigurationProperties} in the
 * {@code config} package. That matters for the dependency rules: {@code config} is the outermost
 * ring and may depend on a capability, but a capability must never depend on {@code config}
 * (docs/architecture/application-structure.md §4). A properties class owned by the capability it
 * configures keeps the arrow pointing the right way.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AssignmentPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssignmentPortalApplication.class, args);
    }

}
