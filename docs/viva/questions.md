# Viva Questions

Use these prompts to explain the design from the code and the repository's evidence. Be explicit
about what has run and what remains unverified. Do not describe an expected result as an observed
result.

## Application architecture

1. Why did this project choose a modular monolith instead of microservices? What deployment and
   data consistency costs would microservices add here?
2. Why use Spring Boot for this application? Which capabilities come from starters and
   auto-configuration, and where is application policy still explicit?
3. Why use the Maven Wrapper instead of relying on Maven installed on each developer's machine?
   Which Maven and Java constraints does the build enforce?
4. Why use embedded Tomcat instead of deploying a WAR to a separately managed application server?
5. How does package-by-feature organization differ from putting all controllers, services, and
   repositories into separate top-level packages? What dependency direction does this repository
   expect?

## Persistence and file handling

6. Why use MySQL as the system of record rather than an in-memory database or a document store?
7. Why use Flyway migrations, and why is Hibernate schema generation set to validate?
8. Why use session authentication rather than JWT for a server-rendered, browser-oriented product?
   How do CSRF, session rotation, cookie attributes, and route authorization fit together?
9. Why does the upload validator inspect PDF magic bytes instead of trusting the file extension or
   client-supplied Content-Type? What does this check not prove about a PDF's safety?
10. Why record a SHA-256 checksum for each uploaded file? How could an operator use it, and what
    does a checksum not provide (for example, encryption or authenticity by itself)?
11. Why keep file bytes in a persistent volume and metadata in MySQL instead of storing the whole
    file in a database row? What consistency problem does that separation introduce?
12. Why use a server-generated storage identifier and keep the original filename only as display
    metadata? How does path normalization help?
13. Why use optimistic locking for reviewer decisions? What should the second reviewer observe
    when another reviewer wins the race?
14. What does submission status history record, and how is it different from the security-event
    audit history required by FR-24? Which part is currently missing?
15. Why are migration compatibility and application rollback connected? What happens if a new
    migration removes a column the previous application version needs?

## Testing and CI

16. Why run persistence tests against MySQL through Testcontainers instead of H2? What does this
    prove and what does it still not prove?
17. Why use Selenium Page Objects? Which browser details belong in a Page Object and which
    assertions belong in the workflow test?
18. Why use Selenium Manager rather than committing a browser driver binary? What dependency does
    this leave on the test machine or CI agent?
19. Why use a Jenkinsfile as code instead of configuring the pipeline only in the Jenkins UI?
20. Why archive Maven reports, screenshots, logs, and the packaged JAR? How does that help diagnose
    a failed build and tie a release to a revision?
21. How does the pipeline prevent a failed test or an ordinary branch build from publishing or
    deploying an image? Which stages have run on the candidate revision?

## Containers and operations

22. Why use a multi-stage Docker build, and why does this Dockerfile package a Maven-built JAR
    instead of running Maven inside the Docker build?
23. Why run the application container as a non-root user? Which paths need write access, and which
    are intended to remain read-only?
24. Why use versioned image tags instead of latest? How does the Git tag relate to the image and
    the version reported at /actuator/info?
25. Why use Docker Compose for this single-host app/database deployment? What role does Ansible
    have that Compose does not?
26. Why use Ansible modules, handlers, and declarative variables? How would you demonstrate
    idempotency, and what should the second converge report?
27. Why keep Ansible provisioning separate from the current Jenkins per-release deploy scripts?
    What does each path manage?
28. What does the rollback script restore, and what does it deliberately leave alone? When would
    backup/restore be needed instead?
29. How does the append-only release ledger decide which version is safe to roll back to? Why is
    the current APP_IMAGE_TAG in .env insufficient evidence that a version worked?

## Secrets and evidence

30. Where do local, Jenkins, and deployment credentials come from? Why should secrets not be
    committed in .env, a Jenkinsfile, a Docker image, or plaintext Ansible variables?
31. What is currently present in ansible/group_vars/vault.yml, and what must happen before it can
    contain real credentials?
32. Which deployment security limitation remains around HTTP/TLS and the Secure cookie flag?
    What must be verified before an Internet-facing deployment?
33. What evidence would you show for the full test pipeline, image, deployment, and rollback? Which
    of those artifacts are currently marked PENDING_CAPTURE?

## Answer discipline

For each answer, name the implementing file or test, explain the trade-off, and distinguish design
intent from a result verified by a real run. The missing FR-24 audit trail, unexecuted full test
suite, unavailable Docker daemon during the review, and unexecuted Ansible/deployment path should be
stated plainly when relevant.
