/*
 * Jenkins Continuous Integration — Online Assignment Portal.
 *
 * Declarative pipeline on a Windows agent. The setup it assumes, the toolchain an
 * agent must provide, and the branch flow it serves are documented in
 * docs/operations/jenkins-ci.md. The gate this file enforces is documented in
 * docs/testing/ci-quality-gates.md.
 *
 * Three rules shape this file, all from CLAUDE.md:
 *   - §7 rule 1: no credential is written here, ever. A build reads secrets from the
 *     environment the agent provides; it never carries one in its own definition.
 *   - §10: the Maven Wrapper is the canonical build mechanism, so every Maven call
 *     below goes through .\mvnw.cmd and never through a Maven that happens to be on PATH.
 *   - §1: the application is a modest thing and the pipeline is the deliverable, so the
 *     stages below are named for what they establish, and each one fails the build on its
 *     own terms.
 *
 * The quality gate, in one sentence: a green build is the only thing that reaches
 * Docker Build, and Docker Build is the only thing that reaches Deployment. Every test
 * stage sets the build result to FAILURE the moment it fails — via catchError, so that
 * the reporting stages still run — and the downstream stages refuse to run at all unless
 * the result is still SUCCESS. A red pipeline therefore cannot release an image and
 * cannot deploy, structurally rather than by convention.
 *
 * Long operational sequences are not written here. Every environment step — start the
 * database, start the application, wait for health, run the browser suite, collect
 * diagnostics, stop the environment — is a script under scripts\ci\, so it can be read,
 * run, and fixed without touching this file, and so a developer runs exactly what CI
 * runs.
 */
pipeline {
    agent { label 'windows' }

    options {
        // Wall-clock on every console line, so a stage that quietly got slower is visible.
        timestamps()
        // A hung build must not hold the agent — and the Docker daemon — forever. Raised
        // from 60 to 90 minutes when the browser suite joined the pipeline: a MySQL start,
        // an application start, and four browser journeys sit on top of the Maven build.
        timeout(time: 90, unit: 'MINUTES')
        // One build at a time for a given branch. Two runs of this pipeline on one agent
        // would race for the same MySQL host port, the same container name, and the same
        // stored-upload volume.
        disableConcurrentBuilds()
        // Keep the last ten builds; the rest are history nobody reads.
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // The Checkout stage performs the checkout, so the implicit one is skipped and the
        // SCM definition has exactly one home.
        skipDefaultCheckout()
    }

    stages {

        stage('Checkout') {
            steps {
                // Fetch the exact revision under test. `scm` is the job's own SCM definition.
                checkout scm
            }
        }

        stage('Validate') {
            steps {
                // Make the agent's environment visible *before* anything depends on it.
                // This stage is meant to be read, not merely to pass: it prints the versions
                // the later stages will actually run under. It stops at the first missing
                // tool, because a missing JDK, Docker or Compose is a real failure — the
                // stages below cannot run honestly without them.
                bat 'java -version'
                bat 'git --version'
                bat 'docker version'
                bat 'docker compose version'
            }
        }

        stage('Build') {
            steps {
                // Compile both source sets before anything runs. A compilation failure is
                // attributed to Build rather than surfacing as "the unit tests failed",
                // which is the least useful way to learn that a class does not compile.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B clean test-compile'
                }
            }
        }

        stage('Unit Tests') {
            steps {
                // Surefire runs the *Test classes only; the pom's excludedGroups keeps the
                // integration and e2e suites out of this phase (docs/testing/test-strategy.md §4).
                // No `clean` here — Build already cleaned, and re-cleaning would discard the
                // classes it just compiled.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B test'
                }
            }
        }

        stage('Integration Tests') {
            steps {
                // Failsafe runs the *IT suite against a real MySQL 8.4 container it starts
                // itself. Both goals are required and neither is redundant: `integration-test`
                // executes the tests but never fails the build on their result; `verify` is
                // what turns a failed integration test into a failed build.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B failsafe:integration-test failsafe:verify'
                }
            }
        }

        stage('Package') {
            steps {
                // Build the executable Spring Boot JAR — the artefact the browser suite is
                // about to be pointed at, and the one that would be deployed. The tests
                // already ran in their own stages, so they are skipped here rather than
                // executed a second time.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B package -DskipTests'
                }
            }
        }

        stage('Start Test Environment') {
            steps {
                // Bring up the world the browser suite runs against: MySQL 8.4, then the
                // packaged JAR started the way an operator starts it, then a readiness gate.
                // Each is a script, not an inline sequence, so the same three steps can be
                // run by hand when CI is red.
                //
                // The readiness gate is not ceremony. `java -jar` returns as soon as the JVM
                // starts, and Flyway and the seeder come later; a suite that begins before the
                // port is listening fails in the browser with a connection error, which reads
                // like a broken page rather than an application that had not finished starting.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat 'scripts\\ci\\start-database.cmd'
                    bat 'scripts\\ci\\start-application.cmd'
                    bat 'scripts\\ci\\wait-for-health.cmd'
                }
            }
        }

        stage('Selenium E2E') {
            steps {
                // The browser suite, against the environment the previous stage started. It
                // runs only the `e2e-tests` Failsafe execution: a bare `verify -Pe2e` would
                // re-run the integration suite that its own stage has already run and
                // published, attributing the same tests to two stages.
                //
                // A failing test has already written its screenshot and page source to
                // target\e2e-artifacts\ by the time this returns (docs/testing/e2e-testing.md §5).
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat 'scripts\\ci\\run-e2e.cmd'
                }
            }
        }

        stage('Publish Reports and Screenshots') {
            steps {
                // Unit, integration and browser results in one place, plus the failure
                // screenshots as build artefacts. This stage runs even when a test failed —
                // every test stage above uses catchError — because a red build with no report
                // attached is the least useful red build there is.
                //
                // The same publishing is repeated in `post { always }`. That is deliberate:
                // this stage covers the ordinary case where a test failed, and the post block
                // covers the case this stage cannot reach — a failure in Checkout, Validate or
                // Start Test Environment, where the pipeline never gets here at all.
                junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml', allowEmptyResults: true

                // A failed browser test's evidence, kept with the build. allowEmptyArchive
                // because a green run writes none — target/ is cleaned each time, so an empty
                // archive means "nothing failed", not "the capture is broken".
                archiveArtifacts artifacts: 'target/e2e-artifacts/**', allowEmptyArchive: true
            }
        }

        stage('Docker Build') {
            // The quality gate, expressed as a precondition rather than a convention.
            //
            // `currentBuild.currentResult` is SUCCESS only while nothing upstream has failed;
            // every test stage sets it to FAILURE the moment it fails. A red pipeline therefore
            // never evaluates this stage's steps, so no image is built and nothing can carry a
            // bad revision forward.
            //
            // The second condition — that a Dockerfile exists — keeps the stage honest today.
            // The multi-stage Dockerfile is Milestone 5 work and CLAUDE.md §13 holds that
            // milestone back until a `clean verify` is green; until it lands this stage is
            // skipped visibly ("Stage skipped due to when conditional") rather than passing
            // while doing nothing, and it activates by itself the day the file appears.
            when {
                allOf {
                    expression { currentBuild.currentResult == 'SUCCESS' }
                    expression { fileExists('Dockerfile') }
                }
            }
            steps {
                // Built locally and tagged with the revision, so the image can be traced to the
                // commit that produced it. No registry push and no credentials: publishing to a
                // registry is part of the delivery pipeline (CLAUDE.md §13), and a push to a
                // registry the pipeline has no business holding is how an image escapes review.
                //
                // The tag is interpolated by Groovy rather than expanded by cmd, so an unset
                // GIT_COMMIT fails the pipeline's own evaluation with a readable message instead
                // of producing a literal `%GIT_COMMIT%` tag that docker rejects.
                bat "docker build -t online-assignment-portal:${env.GIT_COMMIT} ."
            }
        }

        stage('Deployment') {
            // The far side of the quality gate, and deliberately empty.
            //
            // A stage that cannot run today is still worth having here, because the gate is a
            // statement about *this* stage: it is skipped for exactly the reason Docker Build
            // is, and the defect drill in docs/evidence/defect-demonstration.md observes both
            // being skipped on a failed run. Deployment itself — Ansible provisioning, the
            // health check after it, and the rollback path — is Milestone 5 work that
            // CLAUDE.md §13 withholds until there is a green build to deploy.
            when {
                allOf {
                    expression { currentBuild.currentResult == 'SUCCESS' }
                    // Either layout, so that landing the playbooks does not also depend on
                    // guessing where this file thinks they will live: `ansible/` is what the
                    // README's repository map shows, `deploy/ansible/` is where the deployment
                    // assets sit. Whichever appears first activates the stage.
                    expression { fileExists('ansible') || fileExists('deploy/ansible') }
                }
            }
            steps {
                echo 'Deployment provisioning is Milestone 5 work (CLAUDE.md §13); nothing runs here yet.'
            }
        }
    }

    post {

        always {
            // Stop the environment first, so the application's log is closed and complete
            // before it is collected. Best-effort by construction: stop-environment.cmd always
            // exits 0, because a build that is already red must not go redder over a container
            // that would not stop.
            bat 'scripts\\ci\\stop-environment.cmd'

            // One directory holding the application's logs, the browser suite's failure
            // artifacts and the machine-readable test reports. Archived below and, for a run
            // worth keeping, copied into docs/evidence/ by a human.
            bat 'scripts\\ci\\collect-diagnostics.cmd'

            // Repeated from the Publish stage on purpose — see the comment there. Whatever
            // went wrong, and wherever it went wrong, the results are attached to the build.
            junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml', allowEmptyResults: true

            // The runnable JAR (the Boot plugin's sidecar is named *.jar.original and is not
            // matched by *.jar), the diagnostics bundle, and the browser suite's screenshots.
            // Fingerprinting the JAR records its checksum so this exact binary can be traced to
            // every downstream job that consumes it.
            archiveArtifacts artifacts: 'target/*.jar', fingerprint: true, allowEmptyArchive: true
            archiveArtifacts artifacts: 'target/ci-diagnostics/**', allowEmptyArchive: true

            // Leave the agent clean for the next build, and last of all: everything above reads
            // the workspace. Windows agents hold file locks, and a tidy workspace is what stops
            // one build's leftovers from breaking the next one.
            cleanWs()
        }

        failure {
            // The application's own log, on its own, when the build failed. It is already in
            // the diagnostics bundle above, but a console-adjacent copy is the thing a person
            // opens first when a pipeline goes red, and "why did the application not start"
            // is the most common answer they are looking for.
            archiveArtifacts artifacts: 'target/ci-application*.log', allowEmptyArchive: true
        }
    }
}
