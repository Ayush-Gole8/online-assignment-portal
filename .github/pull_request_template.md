<!--
Title format: <type>(<scope>): <imperative summary>
Example:      feat(submission): allow a student to upload a PDF
See CONTRIBUTING.md §3 for the conventions.
-->

## What this changes

<!-- One paragraph. What is different after this PR is merged? -->

## Why

<!--
The problem this solves and the requirement it serves. Link it:
Closes #<issue>   /   Requirement: FR-16 in docs/requirements/mvp.md
-->

## How

<!--
The approach, and any decision a reviewer would otherwise have to reverse-engineer.
Note anything deliberately left out and why.
-->

## Type of change

- [ ] Bug fix (non-breaking change that fixes a defect)
- [ ] New feature (non-breaking change that adds behaviour)
- [ ] Breaking change (existing behaviour changes)
- [ ] Refactor (no behaviour change)
- [ ] Documentation only
- [ ] Build / CI / tooling

## Verification

<!--
State exactly what you ran and what it produced. "Should work" is not verification.
If a check was skipped, say which and why. Paste real output below.
-->

| Check | Command | Result |
| --- | --- | --- |
| Build | `./mvnw clean package` | <!-- pass / fail / not run --> |
| Unit tests | `./mvnw test` | <!-- e.g. 42 tests, 0 failures --> |
| Integration tests | `./mvnw verify` | <!-- pass / fail / not run --> |
| End-to-end tests | `./mvnw verify -Pe2e` | <!-- pass / fail / not run --> |
| Manual check | <!-- what you clicked, in what environment --> | |

<!--
If a command was NOT run, say so plainly. An honest "integration tests not run locally,
Docker unavailable" is far more useful than an unverified tick.
-->

## Self-review checklist

**Correctness**
- [ ] The change does what the description says, and nothing else.
- [ ] I have re-read my own diff before requesting review.
- [ ] No commented-out code, debugging output, or TODO left behind.

**Security** — see `SECURITY.md`
- [ ] No secret, credential, token, or password is added — including in tests and CI files.
- [ ] New configuration is externalised, and no environment-specific value is hard-coded.
- [ ] CSRF protection is not weakened.
- [ ] Authorisation is enforced server-side for any new route or action.
- [ ] Nothing user-supplied reaches a filesystem path, a SQL string, or a shell.

**Architecture** — see `docs/architecture/application-structure.md`
- [ ] Code lives in the feature that owns the concept, not in a generic bucket.
- [ ] No `Utils`, `Helper`, `CommonUtils`, or other shape-named class is introduced.
- [ ] No feature reaches into another feature's repository or entity.
- [ ] No Lombok (or the reason is documented in an ADR).
- [ ] Any architectural decision change is recorded as a new or superseding ADR.

**Tests** — see `docs/testing/strategy.md`
- [ ] New behaviour is tested at the lowest layer that can prove it.
- [ ] No existing test was weakened, skipped, or deleted to make this pass.
- [ ] A bug fix includes a regression test that failed before the fix.

**Documentation**
- [ ] `docs/` is updated where behaviour, structure, or operations changed.
- [ ] `CLAUDE.md` is updated if a rule, command, or the status section changed.
- [ ] The toolchain record is updated if a tool version changed.

## Evidence

<!--
Link or attach proof: test reports, logs, screenshots. Files under docs/evidence/
follow the naming convention in docs/evidence/README.md. Delete this section if the
change genuinely produces no evidence (e.g. a typo fix).
-->

## Screenshots

<!-- For any user-visible change. Before and after, if applicable. Delete if not applicable. -->

## Reviewer notes

<!--
Anything you want the reviewer to look at closely, and any known limitation you are
deliberately accepting in this PR rather than fixing now.
-->

## Known limitations

<!-- What this PR does not do. "None" is almost always the wrong answer. -->
