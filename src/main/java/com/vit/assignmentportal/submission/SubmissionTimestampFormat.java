package com.vit.assignmentportal.submission;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a submission's timestamps for the pages that show them.
 *
 * <p>It exists because Thymeleaf cannot do this reliably in the templates. The
 * {@code #temporals} expression object that formats a {@code java.time} value lives in
 * {@code thymeleaf-extras-java8time}, and Spring Boot 4's Thymeleaf starter does not bring it —
 * the starter resolves to {@code spring-boot-thymeleaf}, which depends on {@code thymeleaf-spring6}
 * and nothing else. A template using {@code ${#temporals.format(...)}} would therefore fail at
 * render time, in the browser, on a page whose tests had all passed.
 *
 * <p>The alternative — letting the template interpolate the {@code Instant} directly — renders
 * {@code 2026-10-01T09:15:30.123456Z}, which is a machine's answer to a person's question.
 *
 * <p>Named for its responsibility, not its category: this is the class that would have been
 * {@code DateUtils} in a repository without the naming rules in {@code CLAUDE.md} §5, and that
 * name is what turns one method into twenty.
 *
 * <p>Package-private, because a display detail of the submission pages is not something the rest
 * of the application should be able to reach for.
 *
 * <p><strong>UTC is stated in the rendered text, not assumed.</strong> The application stores
 * every timestamp in UTC ({@code hibernate.jdbc.time_zone}), so a time shown without a zone would
 * be read by a student as their local time. Labelling it is the difference between a timestamp
 * and a wrong one.
 */
final class SubmissionTimestampFormat {

    /**
     * {@link Locale#ROOT} rather than the request's locale, for the same reason the file size
     * formatter uses it: an application that is English-only should not have its output change
     * shape with the caller's language settings.
     */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private SubmissionTimestampFormat() {
    }

    /**
     * @param instant the value to render, or {@code null} for a decision that has not happened
     * @return the formatted timestamp, or an empty string when there is none — the templates
     *         test for emptiness and render a phrase instead, which keeps "no value yet" out of
     *         the formatter's vocabulary
     */
    static String format(Instant instant) {
        return instant == null ? "" : FORMAT.format(instant);
    }
}
