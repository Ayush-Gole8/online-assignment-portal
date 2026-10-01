package com.vit.assignmentportal.auth;

import com.vit.assignmentportal.common.Role;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates one account per role for local development, so that a developer can sign in as a
 * student, a reviewer, and an administrator without writing SQL by hand.
 *
 * <h2>Why this is not a Flyway migration</h2>
 *
 * <p>{@code data-model.md} §7 originally placed seed data in a versioned migration. That is the
 * wrong mechanism for <em>credentials</em>, and this class is the correction.
 *
 * <p>A migration runs everywhere, exactly once, and is recorded in {@code flyway_schema_history}
 * as applied forever. Seeding a login through one means every environment — including production
 * — receives a predictable account whose password had to be supplied by an environment variable
 * at migration time. That variable is either set, in which case production now holds a password
 * that also exists in a developer's {@code .env}; or it is unset, in which case the migration
 * fails and the deployment stops. Neither is acceptable.
 *
 * <p>This class is a bean instead, and it is guarded three times over:
 *
 * <ol>
 *   <li><strong>{@code @Profile("dev")}.</strong> The bean does not exist under any other
 *       profile. Activating the profile is a deliberate act, not an accident of packaging.</li>
 *   <li><strong>{@code app.seed.enabled=true}.</strong> Defaults to {@code false} in
 *       {@code application.yml} and must be turned on explicitly.</li>
 *   <li><strong>No password has a default.</strong> Each account is skipped unless its own
 *       environment variable is populated. There is no fallback value anywhere in this file, so
 *       a misconfiguration produces a missing account rather than a guessable one.</li>
 * </ol>
 *
 * <p>The net effect is that the worst case for a production deployment is an empty table. A
 * developer, meanwhile, writes the passwords into a git-ignored {@code .env} and never commits
 * them ({@code CLAUDE.md} §7 and §8, {@code SECURITY.md}).
 *
 * <h2>Idempotence</h2>
 *
 * <p>Each account is created only if its email is not already registered. Restarting the
 * application, or running it repeatedly, changes nothing — which is what makes this safe to leave
 * wired up. An existing account is never updated, so a developer who changes the password in the
 * database and then restarts does not have it silently reset underneath them.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DevelopmentUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentUserSeeder.class);

    /**
     * The accounts to seed, and the environment variables that configure each one.
     *
     * <p>The variable names are spelled out rather than derived from the role, so that an
     * operator reading {@code .env.example} and a developer reading this file see the same six
     * strings.
     */
    private static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount("student", "APP_SEED_STUDENT_EMAIL", "APP_SEED_STUDENT_PASSWORD", Role.STUDENT),
            new SeedAccount("reviewer", "APP_SEED_REVIEWER_EMAIL", "APP_SEED_REVIEWER_PASSWORD", Role.REVIEWER),
            new SeedAccount("admin", "APP_SEED_ADMIN_EMAIL", "APP_SEED_ADMIN_PASSWORD", Role.ADMIN));

    private final UserService userService;
    private final Environment environment;

    public DevelopmentUserSeeder(UserService userService, Environment environment) {
        this.userService = userService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Development seeding is enabled and will create any of the {} demo accounts "
                + "that are missing.", ACCOUNTS.size());
        ACCOUNTS.forEach(this::seed);
    }

    private void seed(SeedAccount account) {
        String email = environment.getProperty(account.emailVariable());
        String password = environment.getProperty(account.passwordVariable());

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            // Deliberately a warning rather than an error. A developer who wants only a student
            // account should not have to configure a reviewer and an admin they will not use.
            log.warn("Not seeding the development {} account: set both {} and {} to create it.",
                    account.label(), account.emailVariable(), account.passwordVariable());
            return;
        }

        if (userService.existsByEmail(email)) {
            log.info("The development {} account ({}) already exists; leaving it unchanged.",
                    account.label(), email);
            return;
        }

        userService.create(email, password, account.role());
        // The email is logged and the password is not. A log file is a place credentials leak
        // from, and the developer already has the .env they wrote it into.
        log.info("Created the development {} account ({}).", account.label(), email);
    }

    /**
     * One demo account and the two variables that describe it.
     *
     * @param label            how the account is named in log output
     * @param emailVariable    environment variable holding the login identifier
     * @param passwordVariable environment variable holding the password
     * @param role             the role the account is created with
     */
    private record SeedAccount(
            String label,
            String emailVariable,
            String passwordVariable,
            Role role
    ) {
    }
}
