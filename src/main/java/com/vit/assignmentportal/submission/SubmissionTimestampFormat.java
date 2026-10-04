package com.vit.assignmentportal.submission;

import java.time.Instant;
import java.time.ZoneId;
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
 * <p><strong>Stored in UTC, shown in IST.</strong> The application stores every timestamp in UTC
 * ({@code hibernate.jdbc.time_zone}), which is the right thing for a system of record: an
 * {@link Instant} is a point on the timeline with no zone, so the stored value is unambiguous and
 * does not shift if the host's zone does. The <em>display</em> is a separate decision, and the
 * readers of these pages are at an Indian institution, so this is the one place the instant is moved
 * into {@code Asia/Kolkata} (IST, UTC+5:30) and labelled. Converting here, at the view boundary,
 * rather than storing local time is what keeps the two concerns from contaminating one another —
 * the clock that writes the rows stays {@code systemUTC()} ({@code config.ClockConfiguration}).
 *
 * <p>The zone is <strong>always named in the rendered text</strong>. A time shown without a zone is
 * read by whoever is looking at it as their own local time, and a timestamp read in the wrong zone
 * is a wrong timestamp, not merely an unlabelled one.
 */
final class SubmissionTimestampFormat {

    /**
     * The zone the stored UTC instant is shown in. India observes no daylight saving, so the named
     * zone and a fixed {@code +05:30} offset would render identically today; the named zone is used
     * because it carries the rule rather than a snapshot of it.
     */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * {@link Locale#ROOT} rather than the request's locale, for the same reason the file size
     * formatter uses it: an application that is English-only should not have its output change
     * shape with the caller's language settings.
     *
     * <p>The {@code 'IST'} label is a literal rather than a zone-name token ({@code z}): the short
     * zone name a formatter derives from {@code Asia/Kolkata} is locale-data dependent and can come
     * out as {@code GMT+05:30}, whereas the audience for these pages knows the three letters. The
     * literal and {@link #DISPLAY_ZONE} must be changed together — a test pins the pair.
     */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ROOT)
                    .withZone(DISPLAY_ZONE);

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
