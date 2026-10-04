package com.vit.assignmentportal.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the upload volume can be written to, as {@code /actuator/health}.
 *
 * <h2>What this adds to the health endpoint</h2>
 *
 * <p>Spring Boot's default {@code health} group already reports two pieces of infrastructure: the
 * database ({@code db}, via the connection pool) and the disk the process is running on
 * ({@code diskSpace}, from {@code File.getUsableSpace()}). Neither of them knows about the upload
 * volume. {@code diskSpace} measures the filesystem holding the working directory, which on a
 * container need not be — and normally is not — the mounted volume; the database is a different
 * system entirely. So an application whose volume had been unmounted would report {@code UP} while
 * failing every upload it accepted, which is the worst kind of green.
 *
 * <p>This class closes that gap by asking {@link StorageService#verifyWritable()} on every probe.
 * A failing volume makes the aggregate {@code health} report {@code DOWN}, which is what the
 * pipeline's health-check stage reads and what a container orchestrator would use to stop routing
 * traffic to an instance that cannot do its job.
 *
 * <h2>The failure is published as a status, not as a message</h2>
 *
 * <p>The exception is logged here and <em>not</em> attached to the {@code Health} it returns.
 * Attaching it would put a filesystem path and an errno into the response body the moment anybody
 * set {@code management.endpoint.health.show-details} to {@code always} — and that setting is one
 * line in a configuration file, whereas the rule about not disclosing paths
 * ({@code CLAUDE.md} §7 rule 11) is meant to hold whatever the configuration says. An operator
 * reading the endpoint learns that storage is down; an operator reading the log learns which
 * directory, and why.
 *
 * <p>The contributor is named {@code storage} in the response, derived from this bean's name.
 * Nothing needs configuring for it to appear: {@code management.endpoints.web.exposure.include} in
 * {@code application.yml} already publishes {@code health}, and Actuator collects every
 * {@link HealthIndicator} bean into it.
 */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StorageHealthIndicator.class);

    private final StorageService storageService;

    public StorageHealthIndicator(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Writes to the volume and reports the result.
     *
     * <p>Never throws. A {@link HealthIndicator} that propagated its failure would turn the health
     * endpoint into a 500 with no status in it, leaving a caller — the pipeline, a load balancer —
     * unable to tell "this instance is unhealthy" from "this instance's health endpoint is broken".
     * A {@code DOWN} response says the first, which is the useful answer, and the exception that
     * explains it goes to the log.
     */
    @Override
    public Health health() {
        try {
            storageService.verifyWritable();
            return Health.up().build();
        } catch (StorageException failure) {
            log.error("The upload volume is not writable. Uploads will fail until it is.", failure);
            return Health.down().build();
        }
    }
}
