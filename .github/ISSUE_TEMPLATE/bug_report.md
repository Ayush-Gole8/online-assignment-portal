---
name: Bug report
about: Report something that behaves incorrectly
title: "[Bug] "
labels: ["bug", "needs-triage"]
assignees: []
---

<!--
Before you file:
- Search existing issues for the same problem.
- Never report a SECURITY vulnerability here. See SECURITY.md — report it privately.
- If you can reproduce it, include the exact steps. A bug that cannot be reproduced
  cannot be fixed.
-->

## What happened

<!-- A clear description of the incorrect behaviour. -->

## What you expected to happen

<!-- What should have happened instead, and why you believe that. -->

## Steps to reproduce

1.
2.
3.

**Reproducibility:** <!-- always / intermittent (roughly how often) / happened once -->

## Which layer is affected

<!-- Tick what you know. Leave unticked what you have not checked. -->

- [ ] Upload / file handling
- [ ] Authentication or session
- [ ] Authorisation (a user can see or do something they should not)
- [ ] Workflow status (submission stays SUBMITTED, or a decision does not stick)
- [ ] UI / rendering
- [ ] Configuration or startup
- [ ] Deployment / pipeline
- [ ] Not sure

## Environment

| | |
| --- | --- |
| Revision / image tag | <!-- commit SHA or image tag, e.g. a1b2c3d --> |
| Environment | <!-- local / CI / deployed --> |
| Browser | <!-- if a UI issue --> |
| Java version | <!-- `java -version` --> |
| MySQL version | <!-- if relevant --> |

## Logs and errors

<!--
Paste the relevant log output. REDACT passwords, tokens, connection strings, session IDs,
and any personal data before posting. If redaction was necessary, say so.
-->

```
```

## Impact

<!-- Who is affected, and how badly? Is there a workaround? -->

## Checklist

- [ ] I have searched for an existing issue covering this.
- [ ] I have not included any secret, credential, or personal data in this report.
- [ ] This is not a security vulnerability (those go through SECURITY.md).
