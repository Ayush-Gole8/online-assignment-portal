# Observability

**Status:** Implemented — request identifiers, the logging context, and infrastructure health.
**Not yet executed**: no suite has been run at this revision ([toolchain §9](toolchain.md)). The
health endpoint's output has not been read from a running application.

**Related:** [CLAUDE.md §7](../../CLAUDE.md) · [Error handling](../architecture/error-handling.md) ·
[System overview](../architecture/system-overview.md) · [Toolchain](toolchain.md)

---

## 1. Scope, and what is deliberately absent

The application is designed to be **operable by a person with a terminal and a log file**. That is
the whole of the intent at this stage: when a request fails, the person who saw the failure can quote
one value, and an operator can find everything about that request with `grep`.

Three things are therefore **not** here, on purpose:

| Absent | Why now |
| --- | --- |
| A metrics platform (Prometheus, Micrometer registry, Grafana) | A dashboard nobody is watching is a cost with no reader. The activation decision belongs to the pipeline milestone, when there is a deployed environment and a health check that has actually been pointed at something |
| Distributed tracing (OpenTelemetry, span export) | This is one process. A trace with a single span per request restates the request log |
| Log aggregation and alerting | The log is a file on the host until there is a host to collect from |

What *is* here is the half that has to exist before any of the above is useful: a request has an
identifier, that identifier is in the log, and health is a real answer rather than a liveness lie.

## 2. Request identifiers — `X-Request-ID`

`config/RequestIdFilter` runs ahead of the whole chain and, for every request:

1. Reads `X-Request-ID` from the request.
2. **Uses it if it is acceptable; otherwise generates one.** Acceptable means it matches
   `[A-Za-z0-9._-]{1,64}` — a conservative allowlist, because the value ends up in a log line and in a
   response header, and both are places where a crafted string does damage. A newline in a log field
   forges a second entry; a newline in a header value splits the response. Anything longer than 64
   characters, or empty, is refused and replaced.
3. Sets it on the response **before the chain runs**, so the identifier is on the response a caller
   most needs it for — the one carrying an error.
4. Puts it in the logging context for the duration of the request, and removes it in a `finally`
   block. The container reuses threads; an identifier left behind would be attached to the next
   request that thread served.

Three names for the same value, because three readers need it: the response header `X-Request-ID`
(what the user quotes), the `MDC` key `requestId` (what log patterns interpolate), and the request
attribute `portalRequestId` (`RequestIdFilter.ATTRIBUTE` — for server-side code that needs the value
without reaching into the logging context).

**Why it accepts a client value at all.** This is the feature's purpose, not a concession: a proxy or
load balancer that logged its own identifier must be able to find the same string in this
application's log, or the two cannot be lined up. Refusing every client value would make the filter a
generator rather than a correlation mechanism.

**How to use it.** Ask the user for the `X-Request-ID` on their response, then:

```bash
grep '\[7f3c1a90\]' application.log      # substitute the identifier they gave you
```

Every line contributed while serving that request carries it in brackets after the level, including
the `WARN` or `ERROR` the request ended in. Lines written outside a request — startup, scheduled work
— render `[]`.

That bracketed form is configured rather than default. Spring Boot's default console pattern prints
the tracing keys (`traceId`, `spanId`) and no other entry from the logging context, so with no
tracing configured an identifier would be propagated and never printed. `logging.pattern.level` in
`application.yml` is what puts it on the line; see §3.

> **It is not a security control.** A client can choose its own value, so it proves nothing about
> origin and must never be used for access decisions, rate limiting, or identity. It is a
> correlation token.

## 3. Logging

Levels are set in `application.yml` (`root: INFO`, `com.vit.assignmentportal: INFO`). The failure
levels are a deliberate choice and are documented in
[error handling §7](../architecture/error-handling.md#7-logging): `WARN` for every refusal this
application decides on, `ERROR` with the throwable only for a storage failure — the one case that is
not a request the user got wrong.

The console pattern is overridden for one reason, and it is the difference between the request
identifier working and not working:

```yaml
logging:
  pattern:
    level: "%5p [%X{requestId:-}]"
```

Spring Boot's default pattern renders the level and the logger name and, from the logging context,
only the tracing keys (`traceId`, `spanId`) — a separate *correlation* section. Nothing here
configures tracing. Without the line above, `RequestIdFilter` would put the identifier into the
logging context and no line would ever show it: a value that exists for code to read and is invisible
in the artefact an operator actually has. `%X{requestId:-}` with an empty default means a line
written outside any request (startup, a scheduled task) renders `[]` rather than the pattern text.

Two rules constrain what a line may contain:

- **No secret, full connection string, or session identifier is ever logged**
  ([CLAUDE.md §8](../../CLAUDE.md)). The handlers that attach a throwable attach Spring's own types,
  whose messages name an entity and an identifier — not a credential.
- **Every refusal message is composed for a person** and carries no client-supplied text, no path, no
  SQL, and no configuration value. That is the contract `PortalException` declares, and it is what
  lets the same sentence be logged and shown.

The request identifier is the only correlation mechanism. There is no trace id and no span id, and
adding one would not tell an operator anything the request identifier does not.

## 4. Health — `GET /actuator/health`

### What is published

Only the `health` endpoint is exposed:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

`env`, `configprops` and `beans` are **not** exposed. They would publish configuration, and
`configprops` in particular would print every resolved property — which is the disclosure the whole
externalised-configuration design exists to avoid.

### What the `health` group reports

| Component | Source | What it actually proves |
| --- | --- | --- |
| `db` | Spring Boot's `DataSourceHealthIndicator` | A connection can be taken from the pool and a validation query answered. This is the system of record |
| `diskSpace` | Spring Boot's `DiskSpaceHealthIndicator` | The filesystem holding the default temp location has free space |
| `storage` | `storage/StorageHealthIndicator` (this application) | **The upload volume is writable** — see below |
| `ping` | Spring Boot | The application is running |

### Why an upload-volume check was needed

`diskSpace` answers a question about *space*, not about *the upload volume*. Those are different
failures with the same symptom:

- A volume that was **never mounted** leaves the container writing to its own ephemeral filesystem.
  There is plenty of free space and `diskSpace` is `UP`.
- A volume **remounted read-only**, or a permission change on the host, leaves free space and a
  filesystem that refuses every write. `diskSpace` is `UP`.

In both, `health` said `UP`, uploads failed, and — because the deployment's health check is what the
pipeline gates on — nothing noticed. `StorageHealthIndicator` closes that gap by asking the volume
itself.

It asks through `StorageService.verifyWritable()`, which **writes a one-byte probe file and removes
it**. The write is the point: a permission flag or a `Files.isWritable` check answers what the file
system *claims*, and the failures above are cases where the claim and the behaviour disagree. Not
mocking `Files` in the test is part of the same argument
(`StorageHealthIndicatorTest`).

Three consequences, all deliberate:

- **It leaves nothing behind.** The probe is deleted in a `finally` block, so an endpoint polled every
  few seconds does not accumulate a file per poll. A check that littered the volume while reporting a
  problem would make the problem worse.
- **It creates the storage root if it does not exist.** `verifyWritable` calls
  `Files.createDirectories(root)` before probing, so the first health poll against a
  misconfigured `UPLOAD_STORAGE_PATH` creates an empty directory. Worth knowing when reconciling a
  host: an empty uploads directory is not evidence that an upload was attempted.
- **A failing check publishes a status and no detail.** The exception message names the volume's
  absolute path, and it would be published the moment anybody set `show-details: always`. The
  diagnostic goes to the log instead, and the response says only `DOWN`. `CLAUDE.md` §7 rule 11 is
  not meant to depend on a configuration value.

The indicator **never throws**. A `HealthIndicator` that propagates an exception gives the caller a
`500` with no status at all — strictly less information than `DOWN`, from the endpoint whose entire
job is to answer.

### How the pipeline will use it

Milestone 5's deployment stage polls this endpoint after releasing the artefact, and its rollback
stage fires when it does not go `UP`. `server.shutdown: graceful` is set for the other end of the
same lifecycle, so an in-flight request is not cut off by a release.

Two things that milestone will likely want, and that are **not** configured yet: separate
`liveness` and `readiness` groups (a database blip should stop traffic being routed to an instance
without restarting it), and a health check that distinguishes the two. Neither is added here — a
probe with nothing pointing at it is speculation, and the deployment design is what should decide its
shape.

## 5. Answering "something went wrong" — a short runbook

1. **Get the request identifier** from the response the user is holding (`X-Request-ID`). If they
   cannot supply it, that is itself a finding: the header is set before the chain runs, so it is
   present on every response, including failures.
2. **Find the request in the log** — every line from that request carries the identifier in brackets
   after the level (§2).
3. **Read the status.** `4xx` means the application declined the request and said why in a `WARN`
   line; `5xx` means something broke, and the `ERROR` line has the throwable.
4. **Check `/actuator/health`** if the answer is a `5xx` from storage. `storage: DOWN` means the
   volume, not the code — and the `ERROR` line from the indicator names the path.
5. **Reconcile the volume against the table** if an artefact is missing. `stored_file_name` in
   `submissions` is the name on the volume; the two-phase write means a file can be orphaned only if
   the process died between the write and the commit, and the identifier is what identifies it.

## 6. Gaps

- **Nothing here has been observed.** The health endpoint has not been read from a running
  application at this revision, so the component names in §4 are what Spring Boot's convention
  produces (`StorageHealthIndicator` → `storage`), not what a response showed.
- **No metrics, no tracing, no alerting.** §1 gives the reasoning. The activation decision belongs to
  the pipeline milestone.
- **The log is not structured.** Lines are human-readable text with an `MDC` field, not JSON. A log
  collector would want the latter; there is no collector yet, and JSON logs are worse to read by hand
  in the environment that exists today.
- **A failed health check is only observable by polling it.** With no alerting, nothing tells anyone
  that `storage` went `DOWN` until a deployment's health check or a user notices. That is the accepted
  cost of §1, and it is the first thing a metrics platform would fix.
- **`show-details: never` is the only thing hiding the `db` component's details.** The application's
  own indicator publishes none at all, deliberately, but Boot's `db` indicator does include the
  database name and driver when details are on. The setting is the control there, and it should be
  reviewed before it is ever changed.
