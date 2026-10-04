# Error Handling

**Status:** Implemented — the failure vocabulary, the MVC behaviour, and the validation that refuses
before an exception is ever raised. **Not yet executed**: no suite has been run at this revision, so
what follows describes written and self-reviewed code, not observed output
([toolchain §9](../operations/toolchain.md)).

**Related:** [CLAUDE.md §7](../../CLAUDE.md) · [Security architecture](security.md) ·
[Application structure](application-structure.md) · [Observability](../operations/observability.md) ·
[Testing strategy](../testing/strategy.md)

---

## 1. The three questions a failure has to answer

Every failure in this application is the same three questions:

1. **What happened?** — a feature's own exception type, raised where the rule lives.
2. **Who can fix it?** — the user, or an operator. This is what separates a 4xx from a 5xx, and it is
   the only distinction the user can act on.
3. **What does the caller see?** — a decision made once, in `config`, from the type. Never from a
   message, and never from the exception itself.

The design follows from the third: an exception carries *meaning*, not a response. A feature that
raised `@ResponseStatus(NOT_FOUND)` was making a statement about HTTP from inside `submission`, and
three features doing that is how a vocabulary becomes a scattering.

## 2. The vocabulary

One place states it: `config/PortalExceptionHandler`. Every row below is a handler in that class.

| Exception | Owner | Means | Answer | Logged at |
| --- | --- | --- | --- | --- |
| `SubmissionNotFoundException` | `submission` | No submission that belongs to the caller — either it does not exist or it is somebody else's | `404` | `WARN` |
| `InvalidSubmissionException` | `submission` | An upload this application refused | `400` | `WARN` |
| `InvalidReviewException` | `review` | A decision refused by the reviewer policy | `400` | `WARN` |
| `SubmissionAlreadyDecidedException` | `submission` | The work already has a terminal status (FR-20) | `409` | `WARN` |
| `OptimisticLockingFailureException` | Spring | This transaction lost a race with another writer | `409` | `WARN`, with the throwable |
| `StorageException` | `storage` | The volume could not be read or written | `500` | `ERROR`, with the throwable |
| any other `PortalException` | — | A refusal added later with no mapping of its own | `400` | `WARN` |

Three choices in that table are load-bearing:

- **409 for both conflict cases, not 400.** In each, the request was well-formed and it is the state
  of the resource that refuses it. A reviewer cannot fix either by editing the form, and no status
  could say so while `400` means "your request was malformed" everywhere else.
- **The fallback exists.** Spring selects the most specific handler, so a `PortalException` subclass
  added without a handler of its own lands on `400` rather than falling through to the framework's
  error path. Adding a refusal cannot bypass the vocabulary; the worst case is that it answers a
  slightly less precise code.
- **404 for "somebody else's", deliberately.** A `403` would confirm that the identifier exists and
  belongs to a person — which is the fact an identifier-enumerating caller is trying to learn. The
  ownership clause in the query is what makes "not yours" collapse into "not found" with no special
  case in Java.

### What is not in the vocabulary

| Failure | Answered by | Why not here |
| --- | --- | --- |
| Anonymous access, missing role | Spring Security's filter chain | It runs before `DispatcherServlet`, so nothing MVC-shaped sees these. A handler here would be a second, weaker copy of a policy already enforced — and catching `AccessDeniedException` at this layer risks turning a `403` into a `500` |
| A path variable that will not convert | `DefaultHandlerExceptionResolver` | `400`, before any query runs. Driving `/submissions/not-an-identifier` to a stack trace is the failure mode that leaks most, and the framework already declines it |
| An unmapped URL | `BasicErrorController` | The framework's own `404`. It renders the same error page — see §6 |
| Anything unhandled | The container's error dispatch | No handler claims it, so the response is the generic `500` page. That is the last resort, not a route any code takes on purpose |

## 3. Two tiers, and why the second is the rarer one

**Tier one — the controller owns the form.** An upload the student got wrong, or a decision the
reviewer got wrong, is caught by the controller that rendered the page and turned into a flash
message next to the field it is about. The page comes back with the user's typing still in it.

**Tier two — the failure escapes.** It reaches `PortalExceptionHandler`, which renders the shared
error page. That is the residue rather than the design: a stale bookmark, a hand-edited identifier, a
race between two reviewers, a volume that has gone away. There is no form to return to, which is what
makes a generic page the right answer.

A controller's own `@ExceptionHandler` also wins over the advice, so a feature that wants a specific
response gets one without competing with the global vocabulary.

## 4. Nothing internal is ever rendered

`CLAUDE.md` §7 rule 11 is not a policy this code follows; it is a property the code is shaped to
have:

- `server.error.include-message: never` and `include-stacktrace: never` in `application.yml`.
- **The advice renders no message at all** — not even for a `PortalException`, whose message *is* safe
  to render. Putting it on a generic error page would describe an internal decision to a reader who
  cannot act on it, and the sentence already reached them on the form they came from. The model
  carries one attribute, the status code, and nothing else.
- **`StorageException` is outside the hierarchy.** `PortalException` means "the message is safe to
  render verbatim"; a storage message names a storage identifier so an operator can reconcile the
  volume against the table. Keeping it a plain `RuntimeException` is what makes it structurally
  impossible for a handler to echo it, rather than merely prohibited.
- **No template ships commentary to the browser.** Thymeleaf emits an ordinary HTML comment verbatim,
  so notes are written as parser-level comments (`<!--/* … */-->`) which are discarded before
  rendering. The error page's own comments named `BasicErrorController` and the
  `server.error.include-message` setting that holds it together — publishing a map of that machinery
  to an anonymous reader, on the one page an anonymous reader is most likely to reach.
- **The request identifier is on the response**, so a user can quote it and an operator can find the
  log line (§[observability](../operations/observability.md)). It is an opaque token, not a fact.

Two tests hold this rather than trusting it: `PortalExceptionHandlerTest` asserts every mapping puts
**only** `status` in the model, and `UiRenderingIT` asserts no rendered page contains an HTML comment
and that the error page names no exception class, package, or path.

## 5. Validation, and why there is no `ValidationUtils`

Validation is not one concern here, and pretending otherwise is what produces a utility class that
knows about everything. Each rule sits behind a component named for what it does:

| Rule | Where |
| --- | --- |
| The file is a PDF, at most the configured limit, empty files are refused, the display name has no path or control characters | `storage/FileValidationService` |
| The limit and the accepted format are *settings*, and a nonsensical setting stops the application at startup rather than at the first upload | `storage/UploadProperties` |
| The title and course code are present and within their column widths | `submission/SubmissionService` |
| A rejection needs a reason, an approval must not carry one | `review/ReviewService` |
| The outcome is one of the two terminal statuses | `common/SubmissionStatus.isDecision()` — asked, not restated |
| A decided submission cannot be decided again | `submission/Submission`, on the entity |

Two conventions make the set coherent without a shared class:

- **A refusal is an exception, never a return value.** A result object every caller must remember to
  inspect makes it possible to ignore a refusal and store the file anyway — which would break the
  guarantee `SubmissionService` makes. Naming the refusal makes that impossible to write by accident.
- **A refusal's message is composed for a person**, and never contains text the client supplied, a
  path, a SQL statement, or a configuration value. That is the contract `PortalException` declares.

Where a rule is *restated* elsewhere it is because the earlier check produces a better message for
the common case, and the later one is still the control:

| Restated rule | Earlier enforcement | The control |
| --- | --- | --- |
| Maximum upload size | The servlet container's multipart parser, which refuses before a byte is buffered | `FileValidationService` — the container cannot produce a sentence, and its limit is bound from the same variable precisely so the two cannot disagree |
| Maximum upload size (again) | A `CHECK` constraint on `submissions.file_size_bytes` | Deliberately **not** derived from the setting: it is the only check that does not share a source of truth with the code, so it catches a row written by anything but this application |
| Required fields | `required` on the HTML input | Server-side, always. The attribute is a convenience for the user and never a control |

> **One stale comment, deliberately left.** `V2__submissions.sql`'s `ck_submissions_file_size` says
> its `10485760` matches `FileValidationService.MAX_FILE_SIZE_BYTES`, and no such constant exists any
> more — the limit is now bound by `UploadProperties`. The migration is **frozen**: Flyway validates
> checksums (`validate-on-migrate: true`), so editing a byte of a migration that has already run
> would break every database that has it. The value is still correct; only the comment's pointer to a
> source is out of date. Correcting it means a new migration that alters the constraint, which is not
> worth doing for a comment. Recorded here so the next reader does not go looking for the constant.

## 6. The error page, and the two ways a response reaches it

`templates/error.html` reads exactly one attribute, `status`, and renders a sentence chosen from it:
`404` is "check the address" and `500` is "something went wrong". Two paths arrive there:

- **`config.PortalExceptionHandler`** — a refusal that escaped a controller. Renders the page with the
  status it decided.
- **`BasicErrorController`** — an unmapped URL, a framework failure, an unhandled throwable, or a
  request to `/error` directly. `SecurityConfiguration` permits that path explicitly, because Spring
  Security applies its rules to the `ERROR` dispatch too: without it, a signed-in user who mistyped a
  URL would be bounced to the sign-in page, which reads as "the site is broken" rather than "that
  address is wrong".

The two paths share the view so that a reader cannot tell which one they arrived by — which is the
point. The status is the same number their browser already received.

`BasicErrorController` negotiates between an HTML and a JSON handler by `Accept` header, which is why
the tests drive it the way a container's error dispatch does (an `Accept: text/html` header and an
`ERROR_STATUS_CODE` request attribute) rather than as a bare `GET /error`.

## 7. Logging

| Level | For | Rationale |
| --- | --- | --- |
| `WARN` | Every refusal above | A refusal is also what a hand-edited identifier looks like. The two are indistinguishable to the caller by design, so the log line is the only place "a stale link" and "somebody walking the range" could ever be told apart — and it should not be silent |
| `ERROR` | `StorageException`, with the throwable | Not a request the user got wrong. It is infrastructure that needs an operator, and the identifier in the message is exactly what that operator needs. It stops at the log |

Every line carries the request identifier from the logging context, so the log entry for a response a
user is holding can be found by the value they quote. No secret, connection string, or session
identifier is ever logged ([CLAUDE.md §8](../../CLAUDE.md)); the two handlers that log a throwable log
only Spring's own types, whose messages name identifiers rather than credentials.

## 8. What holds it

| Claim | Test |
| --- | --- |
| Every exception maps to the right status, and the same view | `PortalExceptionHandlerTest` |
| No mapping puts anything but the status code in the model | `PortalExceptionHandlerTest` |
| A storage failure's path never reaches the response | `PortalExceptionHandlerTest` |
| A refusal that escapes a controller renders the error page | `SubmissionAccessIT`, `ReviewWorkflowIT` |
| The refusal explains nothing — no exception class, no package, no identifier | `SubmissionAccessIT`, `ReviewWorkflowIT`, `UiRenderingIT` |
| No rendered page ships template commentary | `UiRenderingIT` |
| A malformed identifier is a `400`, not a stack trace | `SubmissionAccessIT` |

Each of these is a unit test where the question is about the mapping table, and an integration test
where the question is whether the wiring holds — a correct handler that Spring never registers has no
effect at all.

## 9. Gaps

- **No audit trail (FR-24).** ADR-005 requires sign-in attempts, **including failures**, to be
  recorded. This is a *security event* log, a different concern from the workflow history in
  `submission_status_history`, and from the request-correlated application log in §7. It does not
  exist, and [`application-structure.md` §5](application-structure.md) records the unresolved
  question of where such a package would live.
- **The `storage` clock is not the whole application's.** `SubmissionService` and its entities take
  every instant from the injected `Clock`. `auth.User` still calls `Instant.now()` in its lifecycle
  callbacks. That is a real inconsistency, left in place rather than rewriting working persistence
  mapping that a running build has already exercised; it is the next place the clock should reach.
- **Nothing here has been executed at this revision.** See the status line.
