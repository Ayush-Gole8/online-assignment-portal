package com.vit.assignmentportal.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the clock the application reads the time from.
 *
 * <h2>Why a bean rather than {@code Instant.now()}</h2>
 *
 * <p>A call to {@code Instant.now()} is a hidden dependency on the wall clock: it cannot be
 * substituted, so any behaviour that depends on it can only be tested by watching the real time
 * pass. The timestamps this application writes are not decoration. {@code created_at} orders the
 * reviewer's queue, {@code reviewed_at} is what the student is shown as the moment their work was
 * decided, and {@code submission_status_history.created_at} is the workflow's record of when each
 * transition happened (FR-21). A test that wants to assert "the decision was recorded at this
 * instant, and the history row carries the same one" needs to be able to say what that instant is.
 *
 * <p>Injecting a {@link Clock} is the standard seam for this, and it costs nothing at runtime.
 *
 * <h2>Why UTC, and why here</h2>
 *
 * <p>{@link Clock#systemUTC()} rather than {@link Clock#systemDefaultZone()}: the schema stores
 * {@code DATETIME(6)} values that Hibernate is configured to read and write as UTC
 * ({@code hibernate.jdbc.time_zone} in {@code application.yml}), and a clock that reported local
 * time would make the two disagree by however many hours the host is offset — silently, and only on
 * the hosts whose configuration differed. Stating UTC at the source means the JVM's default zone
 * cannot influence a stored timestamp at all.
 *
 * <p>It is declared in {@code config} because it is a cross-cutting framework-neutral library bean,
 * which is what that package is for: a feature may inject it — {@code SubmissionService} does — but
 * no feature imports this class to get it. The alternative, letting each feature construct its own
 * clock, is how two parts of one application end up with two notions of "now".
 */
@Configuration
public class ClockConfiguration {

    /**
     * The application's clock.
     *
     * <p>Declared as {@link Clock} rather than {@code java.time.Clock.systemUTC()}'s concrete return
     * type, so a test can replace it with {@link Clock#fixed} without depending on which
     * implementation the production context happens to wire.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
