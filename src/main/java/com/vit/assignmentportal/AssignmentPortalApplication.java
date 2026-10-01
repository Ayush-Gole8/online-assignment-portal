package com.vit.assignmentportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Git-Driven Online Assignment Submission Portal.
 *
 * <p>A single deployable modular monolith with embedded Tomcat (ADR-001, ADR-002). Feature
 * packages ({@code auth}, {@code submission}, {@code review}, {@code storage}) are added as
 * the features themselves are built — this milestone establishes the runtime only.
 *
 * <p>Component scanning starts here and covers {@code com.vit.assignmentportal}.
 */
@SpringBootApplication
public class AssignmentPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssignmentPortalApplication.class, args);
    }

}
