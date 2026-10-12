# Evidence

Proof that the pipeline did what it claims. This directory holds **captured output from
real runs** — not descriptions of runs, and not examples of what output would look like.

The distinction matters. "The tests pass" is an assertion. A retained Surefire report, a
Selenium screenshot of the completed workflow, and a deployment log showing a green health
check are evidence. A DevOps project is assessed on the second thing.

---

## 1. What belongs here

One directory per pipeline stage, matching the delivery pipeline order:

```
docs/evidence/
├── 01-build/          # mvnw package output, artefact checksums
├── 02-unit-tests/     # surefire reports and summaries
├── 03-integration-tests/  # failsafe reports, testcontainers logs
├── 04-e2e-selenium/   # Selenium reports, screenshots, failure captures
├── 05-docker-image/   # build logs, image digests, size, scan output
├── 06-registry/       # push logs, tags, manifest listing
├── 07-provisioning/   # Ansible run logs (check mode output included)
├── 08-deployment/     # compose up output, running container state
├── 09-health-check/   # health endpoint responses, timestamps
└── 10-rollback/       # rollback drill: what failed, what was restored, health after
```

These directories are created when the first real evidence exists. Creating them now would
produce empty directories that imply work has happened when it has not.

## 2. Naming convention

```
<stage>/<UTC-date>-<commit-sha-short>-<description>.<ext>
```

Example: `02-unit-tests/2026-10-14-a1b2c3d-surefire-summary.txt`

The commit SHA is the important part: it ties a piece of evidence to the exact revision that
produced it. Evidence without a revision is an anecdote.

## 3. Rules

- **Evidence is captured from a real run.** Never hand-write or edit a log, report, or
  screenshot. Sanitise secrets if necessary — and if sanitising is needed, that is a bug to
  fix in the application's logging, not a step to normalise.
- **Failures are evidence too.** A rollback drill that only records success is not a drill.
  Record the failure that triggered the rollback, the action taken, and the health check
  afterwards.
- **Include the timestamp and the revision** for anything time-sensitive.
- **No secrets.** No passwords, tokens, connection strings, or registry credentials in any
  captured file. Check before committing.
- **Screenshots over prose** for anything a user would see — a Selenium screenshot of a
  completed approval is more convincing than a paragraph describing it.
- Keep files small and text-based where possible; large binary artefacts belong in the registry
  or an external store, referenced from here rather than committed.

## 4. What must eventually be demonstrated

| Requirement | Evidence it is met |
| --- | --- |
| A pull request gates on CI | Screenshot of a PR with the Jenkins check, and one with it failing |
| The build is reproducible | Two build runs from the same SHA producing the same artefact checksum |
| Tests prove the workflow | Unit, integration, and Selenium reports for the same revision |
| The image is versioned | Registry listing showing the image tagged with a commit SHA |
| Provisioning is idempotent | Two consecutive Ansible runs, the second reporting no changes |
| Deployment is health-checked | Health endpoint response captured immediately after deploy |
| Rollback works | A recorded drill: deploy a bad revision, observe the failed health check, roll back, observe health restored |
| Uploads survive redeployment | A file uploaded before a redeploy, downloaded and checksum-verified after |

The rollback drill is the one most often skipped and the one most worth having. A rollback
path that has never been exercised is a hypothesis.

## 5. Academic task capture guides

The [task-to-evidence mapping](task-mapping.md) links fifteen project-derived academic tasks to implementation files, repeatable demos, expected outcomes, and the artifact to capture. The repository does not contain the institution's numbered rubric, so align these labels to the official task wording before submission if needed.

Each guide from [task-01.md](task-01.md) through [task-15.md](task-15.md) is a capture plan. Its `PENDING_CAPTURE` markers mean that screenshot, log, or test output has not yet been retained; they are not evidence of a successful run. Follow the capture rules above and replace the marker only after saving the real artifact.
