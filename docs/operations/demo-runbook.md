# Final Demonstration Runbook

**Status:** Written, not executed. This runbook describes the intended sequence for a single
uninterrupted final demonstration. No step has been rehearsed end-to-end. Where external
infrastructure (Jenkins, Docker Hub, a deployment host) is required, each step is marked with
its precondition.

**Related:** [Release Runbook](release-runbook.md) | [Rollback](rollback.md)
| [Ansible](ansible.md) | [Jenkins CI](jenkins-ci.md) | [Troubleshooting](troubleshooting.md)

---

## Prerequisites (verify before the demonstration)

| Prerequisite | How to verify |
| --- | --- |
| JDK 21 on PATH and JAVA_HOME set | `java -version` returns `21.x` |
| Docker Desktop running | `docker version` exits 0 |
| `.env` populated from `.env.example` | Passwords set; no empty required variables |
| Jenkins running and the multibranch job configured | Open Jenkins URL, confirm job exists |
| Jenkins credentials stored: `dockerhub-credentials`, `deploy-ssh-key` | Manage Credentials |
| Docker Hub account with push access to `IMAGE_REPOSITORY` | `docker login docker.io` works |
| Staging host provisioned by Ansible with `assignment-portal.service` active | See Stage 13 |
| `main` branch is green on Jenkins | Last pipeline build is SUCCESS |

---

## Stage 1 -- Git branch

**Goal:** Show the protected `main` branch and the branch strategy.

```bash
git checkout main
git pull origin main
git log --oneline -10
git branch -a
```

**Expected:** `main` is the current branch. Recent commits use Conventional Commits format
(`feat(submission): ...`, `fix(auth): ...`). Remote branches show the feature/* naming
convention.

---

## Stage 2 -- Code change

**Goal:** Make a visible, meaningful code change on a feature branch.

```bash
git checkout -b feature/demo-2026-10-12-final
```

Make a change in the application -- for example, add a visible text to the student dashboard
page (`src/main/resources/templates/submission/list.html`) or update a log message.

```bash
git diff
```

**Expected:** A clean, focused diff. The change is small and testable.

---

## Stage 3 -- Commit

**Goal:** Show the Conventional Commits format.

```bash
git add -p       # stage only the intended change
git commit -m "docs(demo): add demonstration marker to student dashboard

Shows the visible demonstration change required for the final viva.
No functional change to submission logic."
git log --oneline -3
```

**Expected:** The commit message has a type, scope, and imperative summary. The body explains
why, not what.

---

## Stage 4 -- Pull Request

**Goal:** Open a PR against `main` on GitHub and walk through the PR template.

Open `https://github.com/<org>/online-assignment-portal/compare/main...feature/demo-2026-10-12-final`

Fill in the PR template fields:
- Problem statement
- Approach taken
- Verification performed locally
- Checklist: tests pass, no hard-coded secrets, docs updated

**Expected:** The PR template (`.github/pull_request_template.md`) is pre-filled. Required
status checks are listed as pending.

---

## Stage 5 -- Review

**Goal:** Show the code review process.

Walk through the PR diff, pointing out:
- The feature package containing the change
- Absence of hard-coded values
- The test that covers the changed behaviour (or explain why one is not needed for a
  display-only change)

Add a review comment; approve the PR.

**Expected:** The approval is recorded. The merge button is enabled.

---

## Stage 6 -- Merge

**Goal:** Merge the PR to `main`.

Click "Merge pull request" (squash merge or merge commit, per project convention).

```bash
git checkout main
git pull origin main
git log --oneline -3
```

**Expected:** The merge commit appears on `main`. The branch is deleted.

---

## Stage 7 -- Jenkins CI triggered

**Goal:** Show Jenkins picking up the merge commit automatically.

Open the Jenkins job URL. Show the build starting automatically (webhook trigger).

Walk through the declared pipeline options:
- `timestamps()` -- every console line has a wall-clock time
- `timeout(90)` -- hung builds release the agent
- `disableConcurrentBuilds()` -- no race for the database port
- `buildDiscarder` -- keeps last 10 builds

**Expected:** A new build appears within seconds of the merge. The Checkout stage passes.

---

## Stage 8 -- Unit tests

**Goal:** Show the unit test stage passing.

Point at the Validate → Build → Unit Tests stages in the Jenkins pipeline view.

Console output shows:
```
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
```
(or current count -- recorded in CLAUDE.md Section 11)

Show the Surefire report in the Jenkins test results page.

**Evidence to capture:**
- Jenkins stage view screenshot
- Console output showing test count
- Surefire XML or HTML report

---

## Stage 9 -- Integration tests

**Goal:** Show Testcontainers starting MySQL and all integration tests passing.

Console output shows:
```
INFO  org.testcontainers - Creating container for image: mysql:8.4
INFO  Flyway - Successfully applied 3 migrations to schema 'assignment_portal'
[INFO] Tests run: 112, Failures: 0, Errors: 0, Skipped: 0
```
(or current count)

Show the Failsafe report in Jenkins.

**Evidence to capture:**
- Console output showing MySQL container start + Flyway migrations + test count
- Failsafe report

---

## Stage 10 -- Selenium E2E

**Goal:** Show the browser suite running against the packaged application.

Walk through the pipeline stages: Package -> Start Test Environment -> Selenium E2E.

Show `wait-for-health.cmd` waiting for `/actuator/health`, then the E2E suite starting.

Console shows:
```
ChromeDriver started
StudentWorkflowE2E -- upload and verify passed
ReviewerWorkflowE2E -- approve and verify passed
[INFO] Tests run: N, Failures: 0
```

**Evidence to capture:**
- Console output for start-application and wait-for-health
- E2E test report
- Any browser screenshots archived as build artefacts

---

## Stage 11 -- Docker build

**Goal:** Show the Docker image building from the tested JAR.

Point at the Docker Build stage. Console shows:
```
docker build --build-arg APP_VERSION=<commit-sha> -t online-assignment-portal:<sha> .
Step 1/N : ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jre
...
Successfully built <image-id>
```

Explain the two-stage build (unpack / runtime).

**Evidence to capture:**
- Docker build console output
- `docker image inspect` output (shows non-root user, layer structure)

---

## Stage 12 -- Registry push

**Goal:** Show the image publishing to Docker Hub (on a tagged release).

First, create and push a release tag:

```bash
git tag -a v1.0.0 -m "Release 1.0.0 -- final demonstration"
git push origin v1.0.0
```

Jenkins picks up the tag and runs the Publish Image stage:
```
scripts\ops\docker-login.cmd   -- token piped to docker login, never printed
scripts\ops\publish-image.cmd  -- tags <LOCAL_IMAGE>:<version> and pushes
```

Open Docker Hub and show the `1.0.0` tag appearing.

**Evidence to capture:**
- Publish Image stage console (credential IDs, not values)
- Docker Hub repository page showing the tag

---

## Stage 13 -- Ansible provisioning

**Goal:** Show Ansible converging the staging host.

From the Ansible controller:

```bash
# First run (or re-run if already provisioned)
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass

# Second run -- must show changed=0
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass
```

Point at the three roles and explain each one's purpose.

Show:
```
PLAY RECAP
assignment-portal-staging : ok=32  changed=0  unreachable=0  failed=0
```

**Evidence to capture:**
- First-run summary (show what changed)
- Second-run summary (changed=0)
- `systemctl status assignment-portal` on the host

---

## Stage 14 -- Deployment

**Goal:** Show Jenkins deploying the release to the staging host.

The Deploy stage in the Jenkins pipeline:
```
sshagent(['deploy-ssh-key'])
    scripts\ops\deploy-release.cmd
```

Console shows:
- Pre-release gate passing (volumes verified, backup taken)
- `docker compose pull` fetching `1.0.0`
- `docker compose up --no-build --wait` starting the container
- Health check polling `/actuator/health`
- Smoke test passing

**Evidence to capture:**
- Deploy stage console output
- Sanitised (no credentials) deployment log

---

## Stage 15 -- Health check

**Goal:** Show the application answering health and info endpoints.

From the Jenkins agent or locally:

```bash
curl http://<staging-host>:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"}}}

curl http://<staging-host>:8080/actuator/info
# {"app":{"version":"1.0.0"}}
```

**Evidence to capture:**
- Both curl responses

---

## Stage 16 -- Application usage

**Goal:** Show the portal working end-to-end from a browser.

Open `http://<staging-host>:8080`.

1. Sign in as `student` / demo password.
2. Upload a synthetic PDF assignment.
3. View the submission list; confirm status is `SUBMITTED`.
4. Sign out.
5. Sign in as `reviewer` / demo password.
6. Open the review queue; find the submission.
7. Download the PDF.
8. Record an `APPROVED` decision with a comment.
9. Sign out.
10. Sign in as `student` again.
11. Show the submission status is now `APPROVED` with the reviewer's comment.
12. Show the status history.

**Evidence to capture:**
- Browser screenshots at each step
- Network tab showing `Content-Disposition: attachment` on file download

---

## Stage 17 -- Release ledger

**Goal:** Show the append-only release record.

On the host:

```bash
cat /opt/assignment-portal/releases/ledger.tsv
```

Expected output:
```
timestamp       version  environment  outcome   note
2026-10-12T...  1.0.0    staging      success   smoke test passed
```

**Evidence to capture:**
- Contents of `ledger.tsv`

---

## Stage 18 -- Rollback demonstration

**Goal:** Prove that a bad release can be recovered automatically.

**Controlled demonstration approach:**

1. Tag a deliberately broken release (e.g. `v1.0.1` pointing to a commit that fails the smoke
   test or health check). In the demo, simulate by manually editing the image on the host to
   fail, or use the release-rollback-demo stub.

2. Trigger the release pipeline for `v1.0.1`.

3. Jenkins shows the Deploy stage failing after the smoke test.

4. Jenkins console shows `scripts\opsollback-release.cmd` being called automatically.

5. The host shows:
   ```bash
   systemctl status assignment-portal     # still running
   docker ps                              # shows the v1.0.0 image
   curl http://<host>:8080/actuator/info  # {"app":{"version":"1.0.0"}}
   ```

6. The release ledger shows:
   ```
   2026-10-12T...  1.0.1    staging      rollback  smoke test failed; rolled back to 1.0.0
   ```

**Evidence to capture:**
- Jenkins Deploy stage showing rollback trigger
- Host health and version after rollback
- Ledger with the rollback entry

---

## Evidence capture checklist

After the demonstration, collect all evidence into `docs/evidence/`:

- [ ] Stage view screenshot for the PR build and the release build
- [ ] Unit test report (Surefire XML or HTML)
- [ ] Integration test report (Failsafe XML or HTML, showing MySQL container start)
- [ ] Selenium E2E report + screenshots
- [ ] Docker build console output
- [ ] Docker Hub page showing the tagged image
- [ ] Ansible first-run and second-run summaries
- [ ] Deploy stage console (sanitised)
- [ ] `/actuator/health` and `/actuator/info` responses
- [ ] Browser screenshots for all 12 application usage steps
- [ ] `releases/ledger.tsv` after successful deploy
- [ ] Rollback trigger in Jenkins console
- [ ] `/actuator/info` after rollback (showing previous version)
- [ ] `releases/ledger.tsv` after rollback

All items not yet captured are marked `PENDING_CAPTURE` in the individual task evidence files.

---

## What to say when evidence is PENDING_CAPTURE

Be truthful. The correct answer is:

> "This component is implemented and the code is here. No captured run exists yet because
> [specific reason -- e.g. Docker daemon unavailable, Jenkins not yet configured, no deployment
> host provisioned]. The expected output would be [describe what the output should show]. The
> mechanism that would produce it is [describe the command or pipeline stage]."

Do not describe PENDING_CAPTURE evidence as if it exists.
