# Contributing

Thank you for working on the Online Assignment Portal. This document defines **how a change
travels from a local branch to `main`**. The technical rules for the code itself live in
[`CLAUDE.md`](CLAUDE.md); read that first.

---

## 1. Ground rules

- `main` is protected. **Direct pushes are forbidden.** Every change arrives through a pull
  request.
- Every change must be reviewable in one sitting. If a PR needs a summary longer than a
  screen, split it.
- No change is merged with a red pipeline. CI must be green, including the Selenium stage
  once it exists.
- Never commit a secret. See [`SECURITY.md`](SECURITY.md).
- Documentation and code move together. A behavioural change that updates no document is an
  incomplete change.

## 2. Branching

Branch from an up-to-date `main` using a type prefix:

| Prefix | Use for | Example |
| --- | --- | --- |
| `feature/` | New user-visible capability | `feature/submission-upload` |
| `fix/` | Defect repair | `fix/csrf-token-on-upload-form` |
| `docs/` | Documentation only | `docs/adr-006-compose` |
| `chore/` | Build, CI, tooling, dependencies | `chore/pin-mysql-8.4` |
| `refactor/` | Behaviour-preserving restructuring | `refactor/extract-storage-service` |

Use lowercase kebab-case after the prefix. Keep branches short-lived; rebase on `main`
rather than letting them drift.

```bash
git switch main
git pull --ff-only
git switch -c feature/submission-upload
```

## 3. Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <imperative summary>

<why the change is needed — not a restatement of the diff>
```

- **type** — `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, `chore`, `perf`.
- **scope** — the feature package or area: `submission`, `storage`, `security`, `ci`, `docs`.
- **summary** — imperative mood, lowercase, no trailing period, under 72 characters.

The body explains **why**. The diff already explains **what**.

Atomic commits are preferred over one large commit, but a commit must leave the tree
buildable. Do not commit commented-out code or debugging output.

## 4. Pull requests

Open the PR against `main` and fill in the repository template completely. A PR is ready to
merge only when:

1. The title follows the same Conventional Commit convention (it becomes the squash message).
2. The description states the **problem**, the **approach**, and the **verification** actually
   performed — including the exact commands run.
3. The self-review checklist in the template is ticked honestly.
4. CI is green and at least one reviewer has approved.
5. New or changed behaviour is covered by tests at the appropriate layer of the
   [test pyramid](docs/testing/strategy.md).

Reviewers: be specific and kind. Comment on the code, not the author. Distinguish
*blocking* from *nice-to-have*. If a PR changes an architectural decision, it must add or
supersede an ADR under [`docs/adr/`](docs/adr/) rather than editing history silently.

## 5. Definition of done

A change is done when **all** of the following hold:

- [ ] It satisfies a requirement in [`docs/requirements/mvp.md`](docs/requirements/mvp.md),
      or a documented follow-up.
- [ ] It respects the dependency direction in
      [`docs/architecture/application-structure.md`](docs/architecture/application-structure.md).
- [ ] Tests exist at the correct layer and pass locally.
- [ ] `./mvnw clean verify` passes locally.
- [ ] No secret, credential, or environment-specific value is committed.
- [ ] Documentation, ADRs, and the toolchain record are updated where affected.
- [ ] The pipeline is green.

## 6. Local development workflow

Once the build milestone lands, the expected loop is:

```bash
# Start the dependencies (MySQL) the application needs
docker compose -f deploy/docker-compose.yml up -d

# Build and run the full verification suite
./mvnw clean verify

# Run the application against the local database
./mvnw spring-boot:run
```

The exact commands, and the environment variables each one needs, are recorded in
[`docs/operations/`](docs/operations/) as they are established. Until then, this section is
intentionally a target rather than a description.

## 7. Reporting problems

- **Bugs and feature requests** — use the issue templates under `.github/ISSUE_TEMPLATE/`.
- **Security vulnerabilities** — do **not** open a public issue. Follow
  [`SECURITY.md`](SECURITY.md).
