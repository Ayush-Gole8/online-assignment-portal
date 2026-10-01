# ADR-007 — Base package `com.vit.assignmentportal` and the initial package set

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Supersedes:** the base package and package list sketched in
  [application-structure.md](../architecture/application-structure.md) as originally baselined
- **Related:** [ADR-001](ADR-001-modular-monolith.md) · [application-structure.md](../architecture/application-structure.md) · [`CLAUDE.md` §4](../../CLAUDE.md)

## Context

Milestone 0 baselined a base package of `io.github.ayushgole.assignmentportal` and a package
set of `user`, `submission`, `review`, `storage`, `audit`, `security`, `web`.

Implementing Milestone 1 exposed four problems with that baseline:

1. **The base package did not reflect the project's namespace.** The repository is a
   college DevOps project with an institutional namespace, and `io.github.<personal-account>`
   is a personal namespace borrowed from an unrelated hosting convention. A base package is
   the one identifier that is expensive to change later: it appears in every import, every
   component-scan root, and every integration-test package. Deciding it wrongly and
   deferring correction is the expensive path; correcting it now, while one application class
   exists, costs nothing.
2. **`security` named a configuration concern as if it were a domain.** The package held no
   domain concept — only Spring Security wiring. A package named for a *topic* rather than a
   *feature* becomes the next dumping ground, which is the failure mode
   [`CLAUDE.md` §5](../../CLAUDE.md) exists to prevent.
3. **`web` overlapped every feature.** Global web configuration (exception handling, MVC
   setup) and per-feature view concerns (templates, controllers) were split across two
   places with no rule deciding which was which.
4. **`audit` had no defined owner or shape.** It was listed as a capability but never
   specified, and it was unclear whether it was a feature, a capability, or a cross-cutting
   concern.

## Decision

Adopt base package **`com.vit.assignmentportal`**, and start from six packages:

| Package | Kind | Responsibility |
| --- | --- | --- |
| `common` | Shared kernel | Value types, enums, domain exceptions — nothing else |
| `config` | Cross-cutting | Spring configuration: security, MVC, persistence, typed properties |
| `auth` | Feature | Identity: sign-in, sign-out, roles, the `User` entity |
| `submission` | Feature | The student's submission, end to end |
| `review` | Feature | The reviewer's decision |
| `storage` | Capability | File bytes on a volume: store, load, validate, checksum |

Dependency direction: **`config` → features → `storage` → `common`**, acyclic, with `config`
outermost and `common` innermost.

Additionally:

- **Packages are created when code belongs in them**, never in advance. A directory with no
  class in it is not a package ([`CLAUDE.md` §11](../../CLAUDE.md)).
- **`common` is a shared kernel, not a utilities bucket.** It holds value types, enums, and
  domain exceptions. A service in `common` is a review failure.
- The previous `user` package is renamed **`auth`**, because the package owns authentication
  and roles as well as the user record.
- The previous `security` and `web` packages are folded into **`config`**.

## Consequences

**Positive**

- The base package matches the project's institutional namespace and is settled before the
  codebase makes it expensive to change.
- Every remaining package name describes either a **feature** (`auth`, `submission`,
  `review`), a **capability** (`storage`), a **cross-cutting concern** (`config`), or the
  **kernel** (`common`). No name is a topic.
- `config` as the single outermost ring makes the dependency rule checkable: *nothing a
  feature depends on may be configuration.*
- The rename from `user` to `auth` removes a real ambiguity — the package owns sign-in,
  sessions, and roles, not merely the user record.

**Negative / accepted costs**

- **`common` is a name that describes a shape, which this repository otherwise bans.** It is
  admitted here only because a shared kernel is a genuine, recognised pattern and the
  alternative (no shared home for `Role` and `SubmissionStatus`) would scatter enums across
  features and create the very cycles the dependency rule forbids. The cost is real and is
  mitigated by rule 4 above and an explicit reviewer check. If `common` ever holds a class
  with no single nameable responsibility, this decision should be revisited.
- **The audit trail has no home.** FR-24 requires one and `AuditService` is named in
  [`CLAUDE.md` §5](../../CLAUDE.md), but none of the six packages owns it and it cannot live
  in `common` without breaking the kernel rule. This is a **known gap, recorded rather than
  hidden**. See the open item in
  [application-structure.md §5](../architecture/application-structure.md).
- **The package set is closed by this ADR**, so adding a seventh package (for example
  `audit`) requires a superseding ADR rather than an ad-hoc `mkdir`. This is intended: it
  forces the decision to be made deliberately.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **Keep `io.github.ayushgole.assignmentportal`** | Cheapest today and most expensive later — every import and every test package would change. The namespace also misrepresents the project's ownership. Correcting it while the codebase is one file is the last cheap moment. |
| **Keep `security` as a package** | It names a topic, not a responsibility, and would accumulate every "security-ish" class regardless of owner. `config` states what the package actually is: configuration. |
| **Keep `web` alongside feature templates** | Splits one concern (views) across two locations with no rule separating them. Each feature already owns its templates; shared web configuration belongs in `config`. |
| **Keep `user` and add `auth`** | Two packages owning one concept invites the cycle (`user ↔ auth`) that rule 5 forbids, and leaves "where does sign-in live?" permanently ambiguous. |
| **A single `domain` package for entities** | Package-by-layer in disguise. It separates an entity from the service that owns it and its repository — precisely what §1 of this repository's structure document rejects. |
| **`shared` or `core` instead of `common`** | No better. Every synonym for "shared stuff" carries the same risk; the mitigation is the rule and the review, not the spelling. |

## Follow-up

- Decide the home of the audit trail (recommendation: a dedicated `audit` capability package)
  in the milestone that implements FR-24. That decision supersedes or amends this ADR.
- Populate the packages as each feature is built. **Do not create them empty.**
- The next milestone (authentication) is the first to put real code in `auth` and `config`.
