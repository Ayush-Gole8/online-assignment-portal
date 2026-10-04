---
name: CI failure
about: Record a red Jenkins build so it is diagnosed rather than re-run until it passes
title: "[CI] "
labels: ["ci", "needs-triage"]
assignees: []
---

<!--
Use this when the Jenkins pipeline goes red and the cause is not an obvious fix you are
already making on the branch. A red build that is re-run until it passes, without anyone
understanding why it changed, is a defect that has been hidden rather than one that has gone.

If you already know the failing test and the cause, fix it on the branch and let the next
build be the record. Open this issue when the failure needs a decision, points at an agent
rather than the code, or belongs to someone else.
-->

## The failing build

<!--
Link the build. A build URL is evidence; "it was red earlier" is not. Never paste a build
URL that carries a token in its query string — use the plain build page.
-->

- Build URL:
- Revision (commit SHA):
- Branch or pull request:
- First failure, or a repeat of a known one?

## Which stage failed

<!-- Tick the stage Jenkins marked red. See CONTRIBUTING.md §5 for the stages. -->

- [ ] Checkout
- [ ] Validate (toolchain)
- [ ] Unit Tests
- [ ] Integration Tests
- [ ] Package
- [ ] Publish Test Reports / Archive Artifact

## What the log says

<!--
Paste the failing output: the first ERROR or exception, not the whole console. REDACT any
credential, connection string, session id, or personal data before posting, and say that you
did.
-->

```
```

## Was it green before?

<!--
Did this branch ever build green? Did develop? This separates "the change broke it" from
"the agent broke it", and the two are fixed in different places.
-->

## Reproduced locally?

| Command | Result |
| --- | --- |
| `.\mvnw.cmd clean verify` | <!-- pass / fail / not run --> |

<!--
If Docker was not running, say so — an integration stage that failed without a Docker daemon
proves nothing about the code. The same is true of a test that failed for a missing JDK.
-->

## Suspected cause

<!-- One or two lines. "Unknown" is acceptable; a guess presented as a diagnosis is not. -->

## Checklist

- [ ] I have linked the failing build, not only described it.
- [ ] I have redacted every secret and personal data item from the pasted log.
- [ ] This is not a security vulnerability (those go through [`SECURITY.md`](../../SECURITY.md)).
