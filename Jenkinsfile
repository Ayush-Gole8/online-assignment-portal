/*
 * Jenkins Continuous Integration — Online Assignment Portal.
 *
 * Declarative pipeline on a Windows agent. The setup it assumes, the toolchain an
 * agent must provide, and the branch flow it serves are documented in
 * docs/operations/jenkins-ci.md.
 *
 * Two rules shape this file, both from CLAUDE.md:
 *   - §7 rule 1: no credential is written here, ever. A build reads secrets from the
 *     environment the agent provides; it never carries one in its own definition.
 *   - §10: the Maven Wrapper is the canonical build mechanism, so every Maven call
 *     below goes through .\mvnw.cmd and never through a Maven that happens to be on PATH.
 *
 * This pipeline builds, tests and packages only. It does not deploy and it does not
 * publish a container image; those stages belong to the delivery-pipeline milestone.
 */
pipeline {
    agent { label 'windows' }

    options {
        // Wall-clock on every console line, so a stage that quietly got slower is visible.
        timestamps()
        // A hung build must not hold the agent — and Testcontainers' Docker daemon — forever.
        timeout(time: 60, unit: 'MINUTES')
        // One build at a time for a given branch. Two runs of this pipeline on one agent
        // would race for the same Testcontainers MySQL port and the same stored-file volume.
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
                // test stages below cannot run honestly without them.
                bat 'java -version'
                bat 'git --version'
                bat 'docker version'
                bat 'docker compose version'
            }
        }

        stage('Unit Tests') {
            steps {
                // `clean test` runs Surefire over the *Test classes only; the pom's
                // excludedGroups keeps the integration and e2e suites out of this phase
                // (docs/testing/test-strategy.md §4).
                //
                // catchError marks the build FAILURE but lets the pipeline continue, so
                // Publish Test Reports still runs and the report that explains the failure
                // is attached to the build that failed. A red build is never silently green.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B clean test'
                }
            }
        }

        stage('Integration Tests') {
            steps {
                // Failsafe runs the *IT suite against a real MySQL 8.4 container. Both goals
                // are required and neither is redundant: `integration-test` executes the tests
                // but never fails the build on their result; `verify` is what turns a failed
                // integration test into a failed build. These goals use the classes the Unit
                // Tests stage compiled.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B failsafe:integration-test failsafe:verify'
                }
            }
        }

        stage('Package') {
            steps {
                // Build the executable Spring Boot JAR. The tests already ran in their own
                // stages, so they are skipped here rather than executed a second time.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    bat '.\\mvnw.cmd -B package -DskipTests'
                }
            }
        }

        stage('Publish Test Reports') {
            steps {
                // Surefire and Failsafe XML, published as a named stage rather than a
                // post-build step so the report is a visible part of the pipeline.
                // allowEmptyResults lets the stage pass when a suite produced no report at
                // all (for example, a compilation failure) — that failure is already red.
                junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml', allowEmptyResults: true
            }
        }

        stage('Archive Artifact') {
            steps {
                // The runnable JAR only: the Boot plugin's sidecar is named *.jar.original and
                // is not matched by *.jar. fingerprint: true records the artefact's checksum so
                // this exact binary can be traced to every downstream job that consumes it.
                // allowEmptyArchive keeps a package failure from producing a second, confusing
                // error after the build is already red.
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true, allowEmptyArchive: true
            }
        }
    }

    post {
        always {
            // Leave the agent clean for the next build. Windows agents hold file locks, and a
            // tidy workspace is what stops one build's leftovers from breaking the next one.
            cleanWs()
        }
    }
}
