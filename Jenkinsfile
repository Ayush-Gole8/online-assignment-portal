/*
 * Jenkins Continuous Integration and Release — Online Assignment Portal.
 *
 * Declarative pipeline on a Windows agent. The setup it assumes, the toolchain an
 * agent must provide, and the branch flow it serves are documented in
 * docs/operations/jenkins-ci.md. The gate this file enforces is documented in
 * docs/testing/ci-quality-gates.md. The release process — the rules, the
 * credentials, the deployment host, and the rollback — is documented in
 * docs/operations/docker-cd.md.
 *
 * Four rules shape this file, all from CLAUDE.md:
 *   - §7 rule 1: no credential is written here, ever. A build reads secrets from
 *     the environment the agent provides; it never carries one in its own
 *     definition. The two identifiers in `environment` below are the NAMES of
 *     entries in the Jenkins credentials store, which is a different thing from
 *     the values inside them.
 *   - §10: the Maven Wrapper is the canonical build mechanism, so every Maven call
 *     below goes through .\mvnw.cmd and never through a Maven that happens to be on PATH.
 *   - §1: the application is a modest thing and the pipeline is the deliverable, so the
 *     stages below are named for what they establish, and each one fails the build on its
 *     own terms.
 *   - §13: the delivery half of the pipeline is Milestone 5, and this file is where it
 *     arrives.
 *
 * The quality gate, in one sentence: a green build is the only thing that reaches
 * Docker Build, and only a green build of a vX.Y.Z TAG can publish an image or
 * deploy one. Every test stage sets the build result to FAILURE the moment it fails —
 * via catchError, so that the reporting stages still run — and the downstream stages
 * refuse to run at all unless the result is still SUCCESS. A red pipeline therefore
 * cannot release an image and cannot deploy, structurally rather than by convention.
 *
 * What a given revision does, in full:
 *
 *   ref                        CI   image build  publish  deploy
 *   feature/ fix/ docs/ ...    yes  yes (local)  no       no
 *   develop                    yes  yes (local)  no       no
 *   tag vX.Y.Z                 yes  yes          yes      if DEPLOY_ENV is not none
 *
 * The image build on a non-release branch is deliberately not optional. It is the
 * only thing that proves the Dockerfile still builds, and a Dockerfile that has
 * quietly stopped building is a defect that would otherwise be discovered on the
 * day someone tries to release.
 *
 * Long operational sequences are not written here. Every environment step — start
 * the database, start the application, wait for health, run the browser suite,
 * collect diagnostics, stop the environment, identify the release, log in, publish,
 * deploy, smoke test — is a script under scripts\ci\ or scripts\ops\, so it can be
 * read, run, and fixed without touching this file, and so a developer runs exactly
 * what CI runs.
 */
pipeline {
    agent { label 'windows' }

    environment {
        // ------------------------------------------------------------------
        // NAMES of entries in the Jenkins credentials store — not values.
        //
        // An identifier is configuration, which is why it is written down
        // where it can be read and reviewed. The value it refers to is a
        // secret, which is why it appears nowhere in this repository: the
        // stages below bind it into the environment of one stage, for the
        // length of that stage, and nothing else can read it (CLAUDE.md §8).
        // ------------------------------------------------------------------
        DOCKERHUB_CREDENTIALS_ID = 'dockerhub-credentials'
        DEPLOY_SSH_CREDENTIALS_ID = 'deploy-ssh-key'

        // The registry images are published to. Docker Hub, per the release
        // brief. Written down so that a `docker login` and a `docker push` in
        // this pipeline cannot disagree about which host they mean.
        REGISTRY = 'docker.io'

        // The image name the Docker Build stage tags locally, before the
        // Publish stage re-tags it into the registry path. It matches the Maven
        // artifactId, so an image found on a machine names the artefact it came
        // from.
        LOCAL_IMAGE = 'online-assignment-portal'
    }

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

    parameters {
        // ------------------------------------------------------------------
        // The one environment setting this pipeline is parameterised by, and
        // the only input that decides WHERE a release goes.
        //
        // It exists rather than being hard-coded because deploying is not the
        // same decision as releasing. Publishing an image is idempotent and
        // reversible; pointing a host at it is neither, and which host is a
        // decision that belongs to a person, on the day, rather than to a stage
        // that runs at midnight.
        //
        // Jenkins chooses the first entry by default, so an ordinary release
        // lands in staging; production is an explicit selection. `none` publishes
        // the image and deploys nothing, which is what to choose when the release
        // is being staged for someone else to deploy.
        // ------------------------------------------------------------------
        choice(name: 'DEPLOY_ENV',
               choices: ['staging', 'production', 'none'],
               description: 'Where a tagged release is deployed. Ignored by every non-release build. `none` publishes the image without deploying it.')

        string(name: 'IMAGE_REPOSITORY',
               defaultValue: '',
               description: 'Docker Hub repository to publish to, as <account>/<name>. REQUIRED for a release. Deliberately has no default: a guessed account name is a push to the wrong repository.')

        string(name: 'DEPLOY_HOST',
               defaultValue: '',
               description: 'The deployment host as user@host. REQUIRED when DEPLOY_ENV is not `none`. Has no default on purpose - a deployment aimed at the wrong host by an unset variable is the failure the empty default prevents.')

        string(name: 'DEPLOY_BASE_URL',
               defaultValue: '',
               description: 'Origin the smoke test requests, such as http://portal.example.com:8080. Optional; derived from DEPLOY_HOST and APP_HOST_PORT when empty.')

        string(name: 'RELEASE_VERSION',
               defaultValue: '',
               description: 'Optional, and an assertion rather than an input. When set it must equal the version on the git tag of this commit, and the build fails when it does not. It can never introduce a version: the git tag is authoritative.')
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

        stage('Release Version') {
            // Step 1 of the release process: decide whether this build is a release,
            // and of what.
            //
            // The answer comes from the GIT TAG and from nothing else. A tag is
            // created deliberately, on a commit that has already passed CI, and it
            // stays attached to that commit forever — which is what makes a release
            // reproducible and a rollback a version that can be named again. A
            // version typed into a build form has none of those properties.
            //
            // `RELEASE_VERSION` is a parameter, but it is an assertion rather than
            // an input: when set it must agree with the tag, and the script fails
            // the build when it does not. It can never introduce a version.
            //
            // See scripts\ops\release-version.cmd for the rules, including what
            // happens to a tag that is not vX.Y.Z.
            steps {
                bat 'scripts\\ops\\release-version.cmd'
                script {
                    // The script writes a FILE, not an environment variable: Jenkins
                    // runs every `bat` step in its own cmd.exe, so nothing survives
                    // from one step to the next. Absence of the file is the negative
                    // answer — a revision that is not a release.
                    def releaseVersion = fileExists('target/release-version.txt')
                            ? readFile('target/release-version.txt').trim()
                            : ''
                    def shortCommit = (env.GIT_COMMIT ?: 'nogit').take(7)

                    env.RELEASE_VERSION = releaseVersion
                    env.IS_RELEASE = (releaseVersion ==~ /\d+\.\d+\.\d+/) ? 'true' : 'false'

                    // The tag the image carries. A release is tagged with its
                    // version and nothing else — never `latest`, which is a pointer
                    // that moves and therefore not a version at all (ADR-006). A
                    // build that is not a release still gets an image, tagged with
                    // the commit, so the Dockerfile is compiled on every green build
                    // and a Dockerfile that has stopped building is caught here
                    // rather than on release day.
                    env.IMAGE_TAG = env.IS_RELEASE == 'true' ? releaseVersion : shortCommit

                    // The build parameters, copied into the environment explicitly
                    // so the scripts read them the same way whether they were set
                    // by Jenkins, by an operator, or by this block. `DEPLOY_ENV` has
                    // the same default the parameter does: an unset environment must
                    // mean "deploy nowhere", never "deploy somewhere".
                    env.DEPLOY_ENV = params.DEPLOY_ENV ?: 'none'
                    env.IMAGE_REPOSITORY = params.IMAGE_REPOSITORY?.trim() ?: ''
                    env.DEPLOY_HOST = params.DEPLOY_HOST?.trim() ?: ''
                    env.DEPLOY_BASE_URL = params.DEPLOY_BASE_URL?.trim() ?: ''

                    if (env.IS_RELEASE == 'true') {
                        echo "Release: version ${releaseVersion}, image tag ${env.IMAGE_TAG}, target environment ${env.DEPLOY_ENV}."
                    } else {
                        echo "Not a release: this revision carries no vX.Y.Z tag. Image tag ${env.IMAGE_TAG}; nothing will be published or deployed."
                    }
                }
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
            // The second condition — that a Dockerfile exists — kept this stage honest while
            // there was none: it was skipped visibly rather than passing while doing nothing.
            // `Dockerfile` is in the tree now, so this stage runs whenever the build is green —
            // and still never when it is not, which is the point.
            //
            // The image is tagged with the release version on a release, and with the commit
            // on anything else. It is built but not published here: publishing is a separate
            // stage and a separate decision, so that a failure to reach the registry is not
            // mistaken for a failure to compile the image.
            when {
                allOf {
                    expression { currentBuild.currentResult == 'SUCCESS' }
                    expression { fileExists('Dockerfile') }
                }
            }
            steps {
                // `--build-arg APP_VERSION` bakes the version into the image, so the
                // running application can report which release it is — the mechanism
                // that lets a deployment prove it deployed the release it meant to
                // (Dockerfile, application.yml, scripts\ops\smoke-test.ps1).
                //
                // The tag and the argument come from the same Groovy variable in the
                // same command, so the image cannot be tagged one version and identify
                // itself as another.
                //
                // The values are interpolated by Groovy rather than expanded by cmd, so
                // an unset variable fails the pipeline's own evaluation with a readable
                // message instead of producing a literal `%IMAGE_TAG%` that docker rejects.
                bat "docker build --build-arg APP_VERSION=${env.IMAGE_TAG} -t ${env.LOCAL_IMAGE}:${env.IMAGE_TAG} ."
            }
        }

        stage('Publish Image') {
            // Steps 2 to 4 of the release process: authenticate, tag into the registry
            // path, and push the versioned image.
            //
            // Gated on a release, and that gate is what "do not deploy untagged feature
            // builds" means in practice: no vX.Y.Z tag, no published image, and therefore
            // nothing for a deployment to pull. The gate is the same expression the
            // Deploy stage uses, so the two cannot disagree about what a release is.
            //
            // `currentBuild.currentResult == 'SUCCESS'` is repeated here rather than
            // relied upon from the previous stage: every gate in this file is
            // self-contained, so moving a stage cannot silently disable one.
            when {
                allOf {
                    expression { currentBuild.currentResult == 'SUCCESS' }
                    expression { env.IS_RELEASE == 'true' }
                }
            }
            steps {
                script {
                    // A release with nowhere to publish fails here rather than being
                    // skipped. Skipping would be the silent outcome: the tag would
                    // produce a green build that published nothing and deployed
                    // nothing, which is the one result a release must never report.
                    if (!env.IMAGE_REPOSITORY) {
                        error "IMAGE_REPOSITORY is not set. A release publishes to a Docker Hub repository, as <account>/<name>; set the build parameter before releasing."
                    }
                }

                // The credential is bound here and nowhere else, so it exists in the
                // environment of this stage for the length of this stage. `docker-login.cmd`
                // reads the two variables and passes the token to docker over a pipe —
                // never in an argument, never in a file.
                withCredentials([usernamePassword(credentialsId: env.DOCKERHUB_CREDENTIALS_ID,
                                                  usernameVariable: 'DOCKERHUB_USERNAME',
                                                  passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    bat 'scripts\\ops\\docker-login.cmd'
                    bat 'scripts\\ops\\publish-image.cmd'
                }
            }
            post {
                always {
                    // `docker login` leaves the credential in the agent's ~/.docker/config.json,
                    // which outlives the workspace and therefore outlives `cleanWs()`. The logout
                    // is in the stage's own post block rather than the build's, so it runs when
                    // this stage ran and not on the forty builds a day that never logged in.
                    //
                    // Best-effort: a build that has already failed must not go redder over a
                    // logout, and an agent with no stored credential is the state we want anyway.
                    bat 'docker logout docker.io'
                }
            }
        }

        stage('Deploy') {
            // Steps 5 to 11 of the release process: connect to the host, pull the exact
            // version, replace the container, wait for health, smoke test, roll back on
            // failure, and report.
            //
            // Three conditions, all of which must hold, and each of which rules out a
            // different way of putting the wrong thing in front of a user:
            //
            //   - the build is green, so the release was tested;
            //   - the revision is a vX.Y.Z tag, so the release was deliberate;
            //   - DEPLOY_ENV is not `none`, so somebody chose to deploy it, here.
            //
            // The whole sequence — including the rollback — is
            // scripts\ops\deploy-release.cmd. It is a script rather than steps in this
            // file because a deployment that goes wrong is a deployment somebody has to
            // read and re-run, and that is impossible when the sequence exists only as
            // a stage definition.
            when {
                allOf {
                    expression { currentBuild.currentResult == 'SUCCESS' }
                    expression { env.IS_RELEASE == 'true' }
                    expression { env.DEPLOY_ENV != null && env.DEPLOY_ENV != 'none' }
                }
            }
            steps {
                script {
                    if (!env.DEPLOY_HOST) {
                        error "DEPLOY_ENV is '${env.DEPLOY_ENV}' but DEPLOY_HOST is empty. Set the user@host build parameter, or set DEPLOY_ENV to `none` to publish without deploying."
                    }
                }

                // `sshagent` holds the key; this file never sees it. The agent's own
                // known_hosts must already hold the host key — the deploy script does not
                // disable host verification, and the first run against an unprovisioned
                // agent fails with "Host key verification failed", which is the correct
                // outcome rather than an obstacle (docs/operations/docker-cd.md §4).
                sshagent(credentials: [env.DEPLOY_SSH_CREDENTIALS_ID]) {
                    // catchError so the reporting stages still run: the deploy script has
                    // already rolled the host back by the time this returns, and the
                    // report explaining why is what a person needs from the build.
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        bat 'scripts\\ops\\deploy-release.cmd'
                    }
                }
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

            // The release record: which version was published, which environment it went
            // to, whether the smoke test passed, and — when it went wrong — the host's
            // own output and the version the rollback restored.
            //
            // Archiving these unconditionally, with allowEmptyArchive, is what makes a
            // failed deployment diagnosable after the workspace is gone. A build that
            // deploys nothing produces none of these files and is not the worse for it.
            archiveArtifacts artifacts: 'target/deployment-report.txt', allowEmptyArchive: true
            archiveArtifacts artifacts: 'target/smoke-test.txt', allowEmptyArchive: true
            archiveArtifacts artifacts: 'target/ci-deploy.log', allowEmptyArchive: true
            archiveArtifacts artifacts: 'target/release-version.txt', allowEmptyArchive: true

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
