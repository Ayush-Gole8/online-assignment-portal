package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The storage health check: what it reports, and what it must not report.
 *
 * <p>No Spring context. The indicator is a two-line adapter over {@link StorageService}, and the
 * part worth testing — whether writing to a volume actually works, and whether asking leaves
 * anything behind — is entirely in that service. A context would add a second of startup per test
 * and would not touch the file system any differently.
 *
 * <p>Uses real directories rather than mocks, deliberately. The whole value of this check is that it
 * does not trust a permission flag; a mocked {@code Files} would put the flag back.
 */
class StorageHealthIndicatorTest {

    @TempDir
    Path volume;

    @Test
    void reportsUpForAWritableVolume() {
        StorageHealthIndicator indicator = new StorageHealthIndicator(serviceOver(volume));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    /**
     * The case this exists for: a volume that cannot be written to.
     *
     * <p>The path below names a directory inside a regular file, which no operating system can
     * create. That stands in for the deployed failures — an unmounted volume, a read-only remount, a
     * permission change — which cannot be produced portably in a test and all of which fail at the
     * same call, {@code Files.createDirectories}.
     */
    @Test
    void reportsDownForAVolumeThatCannotBeWrittenTo() throws IOException {
        Path notADirectory = Files.createFile(volume.resolve("this-is-a-file"));
        StorageHealthIndicator indicator =
                new StorageHealthIndicator(serviceOver(notADirectory.resolve("inside")));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * The failure is reported as a status and nothing else.
     *
     * <p>A detail here would be an exception message naming the volume's absolute path, and it would
     * be published the moment anybody set {@code management.endpoint.health.show-details} to
     * {@code always}. {@code CLAUDE.md} §7 rule 11 is not meant to depend on a configuration value,
     * so the diagnostic goes to the log instead.
     */
    @Test
    void publishesNoDetailWhenTheVolumeIsUnwritable() throws IOException {
        Path notADirectory = Files.createFile(volume.resolve("this-is-a-file"));
        StorageHealthIndicator indicator =
                new StorageHealthIndicator(serviceOver(notADirectory.resolve("inside")));

        Health health = indicator.health();

        assertThat(health.getDetails()).isEmpty();
    }

    /**
     * Probing leaves the volume as it found it.
     *
     * <p>An endpoint a load balancer polls every few seconds must not accumulate a file per poll,
     * and a check that litters a volume while reporting a problem would make the problem it is
     * reporting worse.
     */
    @Test
    void leavesNoProbeFileBehind() throws IOException {
        StorageHealthIndicator indicator = new StorageHealthIndicator(serviceOver(volume));

        indicator.health();
        indicator.health();

        try (var entries = Files.list(volume)) {
            assertThat(entries).isEmpty();
        }
    }

    /** A service over a real directory, exactly as the application wires one. */
    private static StorageService serviceOver(Path root) {
        return new StorageService(new StorageProperties(root));
    }
}
