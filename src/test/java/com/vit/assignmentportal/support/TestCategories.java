package com.vit.assignmentportal.support;

/**
 * The vocabulary of test categories.
 *
 * <p>Every test class in this project carries exactly one {@code @Tag} naming the layer it belongs
 * to, and the three values are named here rather than repeated as string literals across twenty-odd
 * classes. A misspelled literal would not fail to compile — it would silently create a fourth
 * category, and a test carrying it would run in neither the unit nor the integration phase while
 * appearing to be tagged.
 *
 * <p>The constants must agree with the category names Maven filters on in {@code pom.xml}
 * ({@code excludedGroups} on Surefire and Failsafe, and the {@code e2e} profile's {@code groups}).
 * That coupling is stated in {@code docs/testing/test-strategy.md} §4, because a string in a build
 * file and a string in a Java class cannot be made to reference one another.
 *
 * <p>Tags are used to <em>exclude</em> a category from a phase it does not belong to, and naming
 * ({@code *Test} vs {@code *IT}) is what selects the phase in the first place. Both are deliberate:
 * the naming is the primary mechanism the existing suite already relies on, and the tag is a second,
 * explicit statement of the same fact so that a class named {@code *IT} but tagged {@code e2e} is
 * still kept out of {@code verify}.
 */
public final class TestCategories {

    /** No Spring context, no I/O, milliseconds. Run by Surefire during {@code test}. */
    public static final String UNIT = "unit";

    /** A real MySQL via Testcontainers, or a Spring context. Run by Failsafe during {@code verify}. */
    public static final String INTEGRATION = "integration";

    /**
     * A real browser against a running deployment. Never run by {@code verify}; run only under the
     * {@code e2e} profile. No test carries this tag yet — Selenium is not a dependency
     * ({@code docs/testing/test-strategy.md} §7).
     */
    public static final String E2E = "e2e";

    private TestCategories() {
    }
}
