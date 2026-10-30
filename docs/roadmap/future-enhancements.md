# Future Enhancement Plan and Roadmap

**Project:** Git-Driven Online Assignment Submission Portal
**Repository:** [Ayush-Gole8/online-assignment-portal](https://github.com/Ayush-Gole8/online-assignment-portal)
**Document Status:** Complete Roadmap Document

---

## 1. Overview

While the initial 15-task MVP successfully establishes the end-to-end coursework submission, review, testing, containerization, and automated deployment lifecycle, several strategic enhancements are planned for subsequent development phases to scale the application for campus-wide adoption.

---

## 2. Phase 2: Security & Authentication Modernization

### 2.1 Institutional Single Sign-On (SSO) via OAuth2 and SAML
- Integrate OpenID Connect (OIDC) and OAuth2 protocols allowing students and faculty to authenticate directly via institutional identity providers (e.g., Google Workspace, Microsoft Entra ID).
- Retain local database authentication as an emergency administrative fallback.

### 2.2 Multi-Factor Authentication (MFA)
- Implement Time-based One-Time Password (TOTP) enforcement for Reviewer and Administrator roles to mitigate unauthorized grading and audit alterations.

---

## 3. Phase 3: Academic Workflow & Integrity Extensions

### 3.1 Automated Plagiarism & Originality Scanning
- Connect the upload processing pipeline with plagiarism detection engines (e.g., Turnitin or open-source vector similarity checkers) using an asynchronous message broker (RabbitMQ or Apache Kafka).
- Generate similarity percentage reports accessible directly within the reviewer evaluation view.

### 3.2 Dynamic Rubric Evaluation & Inline Feedback
- Provide reviewers with custom grading rubrics, scoring breakdowns, and inline PDF annotation capabilities.
- Support file attachments in reviewer feedback for annotated corrections.

---

## 4. Phase 4: Cloud-Native Scalability & Infrastructure Orchestration

### 4.1 Kubernetes Deployment via Helm Charts
- Package the application into production-grade Helm charts defining Deployments, Horizontal Pod Autoscalers (HPA), and ConfigMaps.
- Implement ingress routing with TLS termination via cert-manager and Let's Encrypt certificates.

### 4.2 Distributed Object Storage (MinIO / AWS S3)
- Replace local volume file storage with cloud object storage using S3-compatible APIs.
- Utilize pre-signed upload URLs to reduce application server memory consumption during large file submissions.

---

## 5. Phase 5: Observability & Telemetry Enhancements

### 5.1 Prometheus & Grafana Metric Dashboards
- Expose detailed Micrometer application metrics from `/actuator/prometheus`.
- Construct real-time Grafana dashboards tracking JVM garbage collection, active database connections, HTTP error rates, and submission throughput.

### 5.2 Centralized Distributed Logging
- Stream container logs via FluentBit or Promtail into a centralized Grafana Loki / Elasticsearch cluster for correlation and audit analysis.
