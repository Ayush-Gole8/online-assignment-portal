# Merge Conflict Resolution & Git Collaboration Record

**Repository:** `Ayush-Gole8/online-assignment-portal`  
**Milestone:** Task 06 — MVP Completion and Git Collaboration  
**Release Tag:** `v1.0.0`  
**Status:** Resolved & Tagged  

---

## 1. Context and Objective
During parallel feature development across Sprint 1 and Sprint 2, multiple feature branches (`feat/implement-submission-workflow` and `feat/reviewer-workflow`) integrated concurrent changes against shared application files (`pom.xml` and route configuration). This document records the deliberate conflict generation, resolution methodology, and validation steps.

---

## 2. Conflict Scenario & Git Markers

When integrating overlapping changes on concurrent branches, Git halts automatic merging and introduces conflict markers:

```text
<<<<<<< HEAD (main)
        // Production submission validation constraints
        long maxFileSizeBytes = 10 * 1024 * 1024; // 10 MB limit
        boolean requirePdfHeader = true;
=======
        // Extended multi-reviewer configuration
        long maxFileSizeBytes = 10 * 1024 * 1024; // 10 MB limit
        boolean enableOptimisticLocking = true;
        int reviewQueuePageSize = 25;
>>>>>>> feat/reviewer-workflow
```

---

## 3. Resolution Workflow

1. **Inspection:** Identify conflicted files via `git status` (both modified: `pom.xml` and configuration).
2. **Reconciliation:** Retain both the strict 10 MB PDF header validation and the optimistic locking configuration with FIFO queue paging.
3. **Removal:** Eliminate marker lines (`<<<<<<<`, `=======`, `>>>>>>>`).
4. **Staging & Commit:**
   ```bash
   git add src/main/resources/application.yml
   git commit -m "Merge branch 'feat/reviewer-workflow': resolve concurrent configuration conflict"
   ```
5. **Verification:** Run `.\mvnw.cmd test` to ensure zero compilation or regression failures.

---

## 4. Release Tagging (`v1.0.0`)

With all MVP features (Authentication, Submission Upload, File Validation, Review Queue, Approval/Rejection) merged and verified, the release milestone is frozen using an annotated Git tag:

```bash
git tag -a v1.0.0 -m "Release v1.0.0: Functional MVP baseline with authentication, submission upload, and reviewer workflows"
```

Tag verification output:
```text
v1.0.0          Release v1.0.0: Functional MVP baseline with authentication, submission upload, and reviewer workflows
commit a99f3dcabe174bd32b16ee4c3bfbc85d7ee3529f
Author: Ayush Gole <ayushgole1910@gmail.com>
```
