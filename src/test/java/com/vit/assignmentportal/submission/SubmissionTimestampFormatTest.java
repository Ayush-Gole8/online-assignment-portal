package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The display formatter, in isolation.
 *
 * <p>This is a pure unit test — no Spring context, no database — because the thing under test is a
 * function of one {@link Instant} and nothing else. It exists at all because the class had no test:
 * the timestamps it renders are the student's answer to "when was my work decided?", and a
 * silently-wrong zone is the kind of defect that looks right in every screenshot.
 *
 * <p>The assertions are written against the behaviour the class is responsible for — the UTC→IST
 * conversion and the label — rather than the exact month abbreviation, which is the JDK's
 * locale-data decision and not this class's. The full rendering is "{@code 1 Oct 2026, 14:45 IST}";
 * what is pinned below is every part of that the formatter actually computes.
 */
class SubmissionTimestampFormatTest {

    /**
     * An instant stored in UTC is shown at its IST wall-clock time, with the label.
     *
     * <p>09:15 UTC is 14:45 in IST (UTC+5:30) on the same calendar day. Asserting the moved time
     * and the {@code IST} label together is what proves the stored-UTC / shown-IST split is real.
     */
    @Test
    void showsAStoredUtcInstantAtItsIstWallClockTime() {
        String rendered = SubmissionTimestampFormat.format(Instant.parse("2026-10-01T09:15:30Z"));

        assertThat(rendered)
                .startsWith("1 ")
                .contains(", 14:45 IST")
                .doesNotContain("UTC");
    }

    /**
     * The conversion moves the calendar day when the offset crosses midnight.
     *
     * <p>20:00 UTC on the 1st is 01:30 IST on the 2nd. A formatter that merely relabelled UTC as
     * IST without shifting the instant would still read "1 … 20:00", so this is the assertion a
     * pure relabelling could not pass — the one that proves the zone is applied, not printed.
     */
    @Test
    void movesTheCalendarDayWhenTheOffsetCrossesMidnight() {
        String rendered = SubmissionTimestampFormat.format(Instant.parse("2026-10-01T20:00:00Z"));

        assertThat(rendered)
                .startsWith("2 ")
                .contains(", 01:30 IST");
    }

    /**
     * An absent instant renders as empty text, not "null" or a default.
     *
     * <p>A decision that has not happened has no {@code reviewedAt}. The templates test the result
     * for emptiness and render a phrase ("Not yet reviewed") in its place, so the contract this
     * method relies on is an empty string — never the word "null", and never the epoch.
     */
    @Test
    void rendersNoTextForAnAbsentInstant() {
        assertThat(SubmissionTimestampFormat.format(null)).isEmpty();
    }
}
