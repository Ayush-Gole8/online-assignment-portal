# Test Strategy — Execution

**Status:** Implemented — this document describes the suite as it exists and how to run it.
**Companion:** [strategy.md](strategy.md) states the *philosophy* — the pyramid, what each layer
should prove, and the definition of done for a change. **This** document is the operational half:
where the tests live, what each one is tagged, how Maven is wired to run the right ones, and the
exact commands. Where the two disagree, `strategy.md` owns the intent and this document is wrong.

**Related:** [CLAUDE.md §6](../../CLAUDE.md) · [test-data.md](test-data.md) · [strategy.md](strategy.md)

---

## 1. The shape of the suite

The pyramid from `strategy.md` §2, holding its shape against the code that currently exists:

| Layer | Class naming | Tag | Runner | Phase | Classes today |
| --- | --- | --- | --- | --- | --- |
| Unit | `*Test` | `unit` | Surefire | `test` | 17 |
| Integration | `*IT` | `integration` | Failsafe | `integration-test`, `verify` | 11 |
| End-to-end | `*E2E` | `e2e` | Failsafe (profile) | `verify -Pe2e` | 0 — see §7 |

Two mechanisms select a layer, and they are deliberately redundant:

- **The class name** is the primary mechanism, and it is what the plugin defaults already do
  (`*Test` under Surefire, `*IT` under Failsafe). Nothing has to be configured for a correctly
  named class.
- **The JUnit tag** is a second, explicit statement of the same fact, and it is what lets the
  build *withhold* a class from a phase it does not belong to. It exists because the name alone
  cannot express "this is an integration test that must never run in the unit phase" if somebody
  names it `SomethingTest`.

Both are required of a new class. A class with neither is a class nobody runs.

## 2. Where the tests live

Tests mirror the production packages, per `strategy.md` §5 and
[application-structure.md](../architecture/application-structure.md):

```
src/test/java/com/vit/assignmentportal/
  MySqlIntegrationTest.java      the abstract base: one container, one storage root
  auth/            AuthenticationIT, AuthorizationIT, CurrentUserServiceTest,
                   DatabaseUserDetailsServiceTest, PasswordEncoderTest
  submission/      SubmissionUploadIT, SubmissionAccessIT, SubmissionConcurrencyIT,
                   SubmissionFailureHandlingIT, StudentDashboardIT, SubmissionRepositoryIT,
                   SubmissionTest, SubmissionServiceTest, SubmissionControllerTest,
                   SubmissionTimestampFormatTest
  review/          ReviewWorkflowIT, ReviewServiceTest, ReviewControllerTest
  storage/         FileValidationServiceTest, ChecksumServiceTest, StorageIdentifierTest,
                   StorageServiceTest, StorageHealthIndicatorTest, UploadPropertiesTest
  config/          PortalExceptionHandlerTest, RequestIdFilterTest
  support/         TestCategories, TestUserFactory, SubmissionTestData   (fixtures, not tests)
```

`support/` holds no test of its own. It holds the fixtures described in
[test-data.md](test-data.md), which are in **test scope** — they are compiled against the test
classpath and never shipped in the JAR. There are no production test helpers: a class that exists
to make testing easier belongs here, where the production artifact cannot see it.

`ApplicationContextIT` and `UiRenderingIT` sit at the root because they test the application as a
whole rather than one feature.

## 3. The tag vocabulary

The three tag values live in one place, as Java constants:

```java
com.vit.assignmentportal.support.TestCategories
    UNIT        = "unit"
    INTEGRATION = "integration"
    E2E         = "e2e"
```

Every test class carries exactly one `@Tag(...)` naming its layer. The constants are used rather
than string literals because a misspelled literal would not fail to compile — it would silently
create a fourth category, and the class would run in neither phase while looking tagged.

**The tag strings and the Maven group names are the same three strings, and nothing enforces
that.** Java cannot read `pom.xml` and Maven cannot read a Java constant, so the coupling is
stated here and stated again in `pom.xml`: if you rename a category, rename it in both. The
build parameters that carry them are named in §4.

Tags are used to **exclude**, never to include, except in the `e2e` profile. That asymmetry is
deliberate and is the subject of §5.

## 4. Maven wiring

The relevant part of `pom.xml`, in outline:

```xml
<properties>
  <test.group.unit>unit</test.group.unit>
  <test.group.integration>integration</test.group.integration>
  <test.group.e2e>e2e</test.group.e2e>
  <surefire.excluded.groups>${test.group.integration},${test.group.e2e}</surefire.excluded.groups>
  <failsafe.excluded.groups>${test.group.e2e}</failsafe.excluded.groups>
</properties>
```

**Surefire** runs the unit phase over `*Test` and excludes the `integration` and `e2e` groups, so
a `*Test` that was mistakenly tagged `integration` still cannot run here — or, more usefully, a
browser test that was mistakenly *named* `*Test` cannot either.

**Failsafe** runs the integration phase over `*IT` and excludes the `e2e` group. This is the
sentence that keeps a browser out of `clean verify`: an `e2e`-tagged class is filtered out of
the default execution regardless of its name, so `./mvnw clean verify` can never launch a
browser by accident.

**The `e2e` profile** adds a second Failsafe execution that *includes* `**/*E2E.java` and
selects the `e2e` group. Activating it is the explicit act of asking for a browser:

```bash
./mvnw clean verify -Pe2e
```

It sets `failIfNoTests=false` so the profile is inert until the first `*E2E` class is written,
rather than failing a pipeline stage for a test nobody has written yet.

### Why `excludedGroups` and not `groups`

`groups` is an **inclusive** filter: turning it on silently drops every test whose author forgot
a tag. `excludedGroups` is **exclusive**: a test with a missing or forgotten tag still runs, by
its class name, and a tag can only ever *remove* a class from a phase. A forgotten tag therefore
costs nothing; a wrong tag costs only the phase it names. That is the failure mode we want — the
one that loses a test is unacceptable, the one that skips a phase is visible.

## 5. Running the suite

```bash
# The full local verification: compile, unit tests, integration tests (needs Docker)
./mvnw clean verify

# Unit tests only — fast feedback, no Docker
./mvnw test

# Integration tests only — needs a running Docker daemon for Testcontainers
./mvnw failsafe:integration-test

# End-to-end tests only — needs a running deployment and a browser
./mvnw clean verify -Pe2e
```

On Windows PowerShell or `cmd.exe`, use `mvnw.cmd` (`.\mvnw.cmd clean verify`). The wrapper needs
nothing on `PATH`. `clean verify` requires Docker because the integration tests start a real
MySQL 8.4 container (`strategy.md` §3.2, [ADR-003](../adr/ADR-003-mysql-system-of-record.md)).

## 6. What each layer proves today

The intent is in `strategy.md` §3. This is where the suite currently stands against it.

**Unit (17 classes).** The business rules, with no container and no context:

- **Status transitions** — `submission/SubmissionTest`: creation starts `SUBMITTED`; approve and
  reject each set their fields; a second decision of any kind is refused and leaves the first
  intact; a rejection requires a reason and an approval must not carry one.
- **Submission service orchestration** — `submission/SubmissionServiceTest`: the checksum is of
  the bytes that were stored; the row and its history entry carry one clock read; a refused
  upload touches neither the volume nor the database; owner-scoped lookups are scoped *in the
  query*.
- **Reviewer decision policy** — `review/ReviewServiceTest`: which decisions are accepted, which
  are refused, and that a refusal reaches the submission service not at all.
- **Storage rules** — `storage/FileValidationServiceTest`, `StorageIdentifierTest`,
  `StorageServiceTest`, `ChecksumServiceTest` (FIPS 180-4 vectors), `UploadPropertiesTest`.
- **Security decisions** — `auth/DatabaseUserDetailsServiceTest` (the row-to-principal mapping
  and the single `ROLE_`-prefixed authority), `auth/CurrentUserServiceTest` (an anonymous token
  is not a signed-in user), `auth/PasswordEncoderTest`, `config/RequestIdFilterTest`.
- **Failure rendering** — `config/PortalExceptionHandlerTest` (every failure renders one view
  and puts nothing but a status number in the model).

**Integration (11 classes).** Real MySQL 8.4 via Testcontainers, or a real Spring context:

- **Schema and migrations** — `ApplicationContextIT`: the Flyway chain applies, Hibernate
  `validate` passes, and each `CHECK` and foreign key is exercised so a constraint is never one
  typo away from being a comment.
- **Repositories** — `submission/SubmissionRepositoryIT`: each finder's ordering, its
  owner-scoping, and its paging, called directly against the table.
- **Persistence and transactions** — `SubmissionUploadIT`, `SubmissionFailureHandlingIT`: the
  two-phase file/row write and what happens when the row does not commit.
- **Concurrency** — `SubmissionConcurrencyIT`: two transactions deciding one submission leave
  exactly one decision (`@Version`).
- **Workflow and audit history** — `review/ReviewWorkflowIT`: approve, reject, the history rows,
  and the student reading their own decision.
- **Security integration** — `AuthenticationIT`, `AuthorizationIT`: sign-in, CSRF, session
  rotation, and role rules through the real filter chain.
- **Ownership** — `SubmissionAccessIT`, `StudentDashboardIT`, `UiRenderingIT`.

## 7. Known gaps

Honest statement of what this suite does not yet do:

- **No end-to-end tests.** Selenium is not a dependency, no `*E2E` class exists, and the `e2e`
  profile is the mechanism and not yet the evidence. This is the Milestone 5 work
  (`CLAUDE.md` §13).
- **No captured evidence.** `docs/evidence/` holds no test output for any milestone, and the
  last recorded green `clean verify` predates Milestones 3 and 4.
- **The audit trail is untested because it does not exist** (FR-24): no `AuditService`, no
  `audit_events` table. `submission_status_history` records *workflow* transitions, which is a
  different concern from *security* events.
- **No coverage measurement.** Per `strategy.md` §1 that is a symptom to inspect and never a
  target, so it is not wired in — but it also means nothing mechanical would notice a whole
  behaviour going untested.

## 8. Adding a test

1. Put it in the package that mirrors the feature, and name it for the layer (`*Test` or `*IT`).
2. Tag it with the matching `TestCategories` constant.
3. State the behaviour in the method name (`refusesAnOverlongTitle`), not the mechanism.
4. Take the data you need from [test-data.md](test-data.md) rather than writing a fourth copy of
   "a valid PDF".
5. Fix the clock. `SubmissionService` takes a `Clock`; a test that tolerates "sometime around now"
   cannot state which instant an event should carry.
