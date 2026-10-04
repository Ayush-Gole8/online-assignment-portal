# Test Data

**Status:** Implemented — describes the fixtures in `src/test/java/.../support/`.
**Related:** [test-strategy.md](test-strategy.md) · [strategy.md](strategy.md) §4 · [CLAUDE.md §7](../../CLAUDE.md)

---

## 1. Purpose

Every fixture here exists because more than one test needed the same thing and the copies had
started to drift. A fixture is in **test scope** — it is compiled against the test classpath and
never reaches the JAR — and it holds no production-facing helper, so there is nothing for the
application to depend on.

There are three classes, all in `com.vit.assignmentportal.support`:

| Class | What it provides |
| --- | --- |
| `TestCategories` | The `unit` / `integration` / `e2e` tag constants |
| `TestUserFactory` | Real accounts and real sign-in sessions |
| `SubmissionTestData` | The PDF constant, a row builder, and a checksum oracle |

## 2. `TestUserFactory`

```java
TestUserFactory users = new TestUserFactory(userService, userDetailsService);

String  email   = users.emailFor("upload", Role.STUDENT);   // "upload-student@example.com"
UUID    id      = users.idFor("upload", Role.STUDENT);      // creates the row if absent
AuthenticatedUser p = users.principalFor("upload", Role.STUDENT);
MockHttpSession s = users.signIn(mockMvc, "upload", Role.STUDENT);
```

### Real accounts, real sign-in

The factory does not fabricate a principal. `idFor` and `principalFor` create a real row through
`UserService` — so the password is BCrypt-hashed on the way in, exactly as a real one is — and
`principalFor` then loads it through `DatabaseUserDetailsService`, which is what Spring Security
itself calls. The identifier on the returned principal therefore names a row that exists, so a
submission written against it satisfies its foreign key rather than failing later for a reason
unrelated to the test.

`signIn` goes one step further: it drives a real `POST /login` through MockMvc with a real CSRF
token and returns the session that came back. That session was produced by the application's own
filter chain, which is what makes the authorisation tests tests of the *configuration* rather
than of a test harness. A test that placed a principal into the security context by hand would
pass while proving nothing about the security configuration.

### Why one slug can mean two people

`emailFor` folds the role into the address (`"upload-student@example.com"`,
`"upload-reviewer@example.com"`), so a single slug stands for "a student and the reviewer who
decides their work" — which is what a two-account workflow test needs — without the caller
threading two emails between calls.

### The password is a throwaway, not a secret

```java
TestUserFactory.PASSWORD = "correct-horse-battery-staple"
```

This is a literal on purpose. It is the credential for disposable accounts on a container this
suite creates and discards; `CLAUDE.md` §7 rule 1 is about real secrets, and a password that
exists only inside a test JVM is not one. It is still hashed before storage, because the hash
path is part of what the sign-in tests exercise.

## 3. `SubmissionTestData`

```java
MockMultipartFile pdf    = SubmissionTestData.pdf();                  // "essay.pdf", application/pdf
MockMultipartFile pdf2   = SubmissionTestData.pdf("report.pdf");      // a chosen name
MockMultipartFile bad    = SubmissionTestData.notAPdf();              // rejected by validation
Submission         row   = SubmissionTestData.draft(studentId, instant);
String             digest = SubmissionTestData.sha256Hex(bytes);
```

### The PDF is a constant

```java
PDF_BYTES = "%PDF-1.7\n%%EOF\n"   // 15 bytes, starts with the %PDF- signature
```

A great many tests need "a valid upload", and before this class each wrote its own `%PDF-1.7`
header. Two tests that disagreed about what a valid PDF looked like would be testing two
different inputs while both claiming to test "a valid PDF", and the disagreement would only
surface as one of them failing for a reason that looked like a bug in the code.

The bytes begin with the signature `FileValidationService` checks, so they pass validation. They
are deliberately not a real document — nothing under test parses them — and they are short
enough to read in an assertion failure.

`notAPdf()` carries the PDF's own bytes under a name that is not a PDF (`essay.docx`), so the
refusal it produces is the extension check's: the cheapest and clearest of the validation rules,
not a signature failure that would only trigger later.

### The checksum is an independent oracle

`sha256Hex` recomputes a digest with the JDK's own `MessageDigest`, **not** with the
application's `ChecksumService`. An integration test asserting "the stored checksum is the digest
of the stored bytes" then compares the application's answer against a second, separate
implementation of the same standard — which is the only way that assertion can fail. Comparing
the application's output against itself would pass for any deterministic function.

### The row builder earns its place

`Submission.upload` takes nine arguments and a test that needs a stored row rarely cares about
all nine. `draft(studentId, createdAt)` supplies the ones that are not the point — a title, a
course code, a file name, a fresh random storage identifier — so a test says only what it is
arranging. Every default is valid against the schema's constraints, so a test that fails does so
for its own reason rather than because a fixture default was rejected:

| Constant | Value | Constraint it satisfies |
| --- | --- | --- |
| `DEFAULT_TITLE` | `"Coursework submission"` | `submissions.title` width |
| `DEFAULT_COURSE_CODE` | `"CS101"` | `submissions.course_code` width |
| `DEFAULT_FILE_NAME` | `"essay.pdf"` | the `.pdf` extension validation requires |
| `PDF_CONTENT_TYPE` | `"application/pdf"` | the one media type the portal accepts |

The storage identifier in `draft` is a **fresh random UUID** each call, because
`stored_file_name` is constrained to that canonical form and two rows sharing one would collide
on its unique index.

## 4. Time is a value the test chooses

Nothing here reads `Instant.now()`. `SubmissionService` takes a `java.time.Clock`, so a test
passes `Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC)` and every timestamp it
asserts is that literal. A test that tolerated "sometime around when the test ran" could neither
notice a second, later clock read in the service nor state which instant an event should carry —
and the one-instant-per-event rule is the whole reason the clock is injected.

The same rule applies to identifiers: a test that needs a stable id writes a literal UUID, and
one that needs freshness (a storage identifier) asks the fixture for a generated one. Nothing
depends on the wall clock or on a random value it did not ask for.

## 5. Isolation: distinct data, one discarded container

The integration suite shares **one** MySQL container for the whole JVM run — started once by
`MySqlIntegrationTest` and discarded at exit — and one temporary storage root. Tests therefore do
not roll back; they **isolate by using data nobody else uses**: each test calls `TestUserFactory`
with its own slug, so the rows it writes are never the rows another test asserts on.

This is a deliberate choice against `strategy.md` §4's "inside a transaction that rolls back".
A rollback-in-every-test approach hides the fact that the queries run against a table which keeps
growing, as a real one does, and it cannot be used for the concurrency tests, which need two
transactions to actually commit. The constraint the strategy states — "tests never depend on a
shared, pre-existing database" — still holds: the container is created empty for this run and
the data is created by the tests, not assumed.

A test that asserts a *global* set (the review queue, for instance) must therefore not assert
`containsExactly`. `SubmissionRepositoryIT.returnsOnlyAwaitingWorkOldestFirst` states the
ordering property (`isSorted`) and the membership it created, rather than a list that other
classes also contribute to.

## 6. What not to do

- **Do not add a helper that exists only to serve one test.** If one test needs it, it belongs in
  that test; put it here only when a second test needs it too.
- **Do not put a fixture in `src/main`.** Test-only concerns never ship.
- **Do not hard-code a real credential**, here or anywhere. The one password in this package is a
  throwaway for a container that is destroyed at the end of the run.
- **Do not assert against `Instant.now()`-derived values** or against a random identifier you did
  not ask the fixture for.
