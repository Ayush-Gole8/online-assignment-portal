# UI screenshots — capture checklist

The end-user UI has been implemented and **none of it has been photographed**. This file is
the list of screenshots that must be captured once a build is green, so that the UI work has
evidence the way the pipeline stages will.

## Why this is a checklist and not a folder of images

`docs/evidence/README.md` §1 and §3 are explicit on two points, and both apply here:

- **Empty directories are not created ahead of the work.** A `docs/evidence/04-e2e-selenium/`
  directory holding nine empty PNGs would imply captures were taken when none were. The
  directory appears when the first real capture lands in it.
- **Evidence is captured from a real run, never hand-written.** A screenshot of a page that
  was never rendered is not evidence; it is an illustration.

So what follows is the specification. Each entry names the file that must exist after the
Selenium stage has run, and what the frame must show. The images themselves are produced by
that run — at which point this section is replaced by a table of captured files with their
revisions, per `README.md` §2:

```
04-e2e-selenium/<UTC-date>-<commit-sha-short>-<description>.png
```

Example: `04-e2e-selenium/2026-10-14-a1b2c3d-student-dashboard.png`

`docs/evidence/` currently holds no captured output for any milestone — not for the pipeline,
and not for the UI. This file does not change that; it records what is owed.

---

## 1. The pages to capture

Every screenshot is a full viewport at **1280×800**, signed in as the role the page belongs
to, with the application seeded from the `dev` profile. Each is captured twice where a state
matters — once empty, once populated — because the empty state is a distinct rendering and
the one most likely to regress.

| # | Page | Route | Role | What the frame must show |
| --- | --- | --- | --- | --- |
| 1 | Sign in | `/login` | anonymous | The form with an accessible label on each field. |
| 2 | Sign in, refused | `/login?error` | anonymous | The generic refusal — **the same sentence for a wrong password and an unknown address.** |
| 3 | Dashboard, empty | `/student/dashboard` | STUDENT | Three zero counts, the empty-state sentence, and the upload call to action. |
| 4 | Dashboard, populated | `/student/dashboard` | STUDENT | Non-zero counts, and the recent-submissions table with a status badge per row. |
| 5 | Upload form | `/submissions/new` | STUDENT | The three fields, each with its label, and the file input restricted to PDF. |
| 6 | Upload refused | `/submissions/new` | STUDENT | A field-level error beside the input it is about, with `aria-invalid` set. |
| 7 | Submission list | `/submissions` | STUDENT | The status badge column, and the pager when there is more than one page. |
| 8 | Submission detail | `/submissions/{id}` | STUDENT | The checksum, the download button, the decision panel, and the chronological history. |
| 9 | Submission detail, rejected | `/submissions/{id}` | STUDENT | The reason for rejection, under its own heading, on the student's own page. |
| 10 | Review queue | `/review/queue` | REVIEWER | The queue of `SUBMITTED` work, oldest first, with the empty state captured separately. |
| 11 | Review detail | `/review/submissions/{id}` | REVIEWER | The artefact metadata, the history, and the Approve / Reject form. |
| 12 | Not permitted | any `STUDENT` route | REVIEWER | The 403 page, rendered with the signed-in header and a working sign-out. |
| 13 | Not found | `/no-such-page` | any | The error page showing a status and a sentence, and **no trace, path, or class name.** |

## 2. The properties a screenshot must be able to demonstrate

Some of the UI work is a claim about behaviour, and a still frame does not prove it on its
own. These are listed so the capture session records what settles each one — a screenshot
plus a line of context, or a second frame.

| Claim | What settles it |
| --- | --- |
| The workflow completes with JavaScript disabled | A capture with the Selenium driver's scripting **off**, showing a decision recorded through this UI. NFR-2. |
| Layout is responsive | The same page at 375×812 as well as 1280×800 — the navbar wraps rather than collapsing, because the collapse needs JavaScript. |
| Validation is server-side, not cosmetic | A capture of a refusal produced by a **hand-crafted request** that bypassed the browser's own checks, not one the browser blocked. |
| Isolation holds | A capture of a second student's list, showing the first student's work is absent rather than merely unlinked. |
| The decision is terminal | A capture of the review page for an already-decided submission: the form is gone, and the outcome that stands is shown. |
| Errors disclose nothing | The error frame in row 13 above, showing the status and the sentence and nothing else. |

## 3. Before committing any capture

- [ ] The image came from a real run of the application, against a real MySQL.
- [ ] No password, session identifier, connection string, or checksum of a real credential is
      visible in the frame.
- [ ] The filename carries the UTC date and the short commit SHA of the revision that produced
      it (`README.md` §2).
- [ ] The capture is small. A screenshot is not an artefact to keep at full resolution; crop to
      the viewport.
- [ ] This file has been updated to link the capture, or the row it belongs to has been marked
      captured — so the checklist does not claim work that is now done.
