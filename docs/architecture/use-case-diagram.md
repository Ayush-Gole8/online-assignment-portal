# Use Case Diagram & System Actor Interactions

**Project:** Git-Driven Online Assignment Submission Portal  
**Document:** System Use-Case Specifications & UML Interaction Model  
**Status:** Baselined  

---

## 1. Actors

| Actor | Role Type | Responsibilities |
| :--- | :--- | :--- |
| **`STUDENT`** | Primary User | Signs in, uploads PDF assignments (<= 10 MB), views personal submission status history, and downloads uploaded submissions. |
| **`REVIEWER`** | Primary User (Faculty) | Signs in, accesses the FIFO review queue of pending `SUBMITTED` work, inspects assignment files, and enters terminal decisions (`APPROVED` or `REJECTED`) with comments. |
| **`ADMIN`** | Administrative User | Manages user accounts and roles, inspects end-to-end submission status histories, and reviews system audit trails. |
| **`PORTAL SYSTEM`** | Automated Background Boundary | Validates uploaded PDF content/magic bytes, computes SHA-256 digests, coordinates Flyway schema migrations, and exposes health probes. |

---

## 2. UML Use Case Diagram

```mermaid
flowchart TD
    subgraph Boundary ["Online Assignment Submission Portal"]
        UC1((Sign In / Authenticate))
        UC2((Upload Assignment PDF))
        UC3((View Personal Submissions))
        UC4((Download Submitted File))
        UC5((Track Review Status))
        
        UC6((View Pending Review Queue))
        UC7((Download File for Review))
        UC8((Approve Submission))
        UC9((Reject Submission with Comment))
        
        UC10((Manage Users & Roles))
        UC11((Inspect Submission History))
        UC12((View Security Audit Trail))
        
        UC13((Validate PDF & Magic Bytes))
        UC14((Compute SHA-256 Checksum))
        UC15((Persist Metadata to MySQL))
        UC16((Expose /actuator/health))
    end

    Student["👤 STUDENT"]
    Reviewer["👤 REVIEWER"]
    Admin["👤 ADMIN"]
    System["⚙️ SYSTEM / PIPELINE"]

    Student --> UC1
    Student --> UC2
    Student --> UC3
    Student --> UC4
    Student --> UC5

    Reviewer --> UC1
    Reviewer --> UC6
    Reviewer --> UC7
    Reviewer --> UC8
    Reviewer --> UC9

    Admin --> UC1
    Admin --> UC10
    Admin --> UC11
    Admin --> UC12

    UC2 -.->|<<include>>| UC13
    UC2 -.->|<<include>>| UC14
    UC2 -.->|<<include>>| UC15

    System --> UC13
    System --> UC14
    System --> UC15
    System --> UC16
```

---

## 3. Use Case Descriptions

### UC-01: Upload Assignment (Student)
* **Pre-conditions:** User authenticated with `ROLE_STUDENT`.
* **Flow:**
  1. Student selects assignment and chooses a PDF file.
  2. System verifies file is <= 10 MB and inspects byte signature (`%PDF-`).
  3. System assigns UUID storage identifier and calculates SHA-256 digest.
  4. Record persisted in MySQL in status `SUBMITTED`.
* **Post-conditions:** File safely stored on volume; entry appears on student dashboard.

### UC-02: Review Submission (Reviewer)
* **Pre-conditions:** User authenticated with `ROLE_REVIEWER`.
* **Flow:**
  1. Reviewer navigates to FIFO `/review/queue`.
  2. Reviewer downloads and examines the student PDF.
  3. Reviewer submits decision (`APPROVED` or `REJECTED`) along with feedback comment.
  4. Optimistic lock (`@Version`) prevents concurrent decision collision.
* **Post-conditions:** Terminal state recorded; transition logged in `SubmissionStatusHistory`.

### UC-03: User Administration & Audit (Admin)
* **Pre-conditions:** User authenticated with `ROLE_ADMIN`.
* **Flow:**
  1. Admin manages user access and inspects overall submission history across all students.
  2. Admin tracks system health and operational readiness via `/actuator/health`.
