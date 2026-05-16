/*
 * Enterprise DevSecOps Jenkins Pipeline Blueprint
 * ------------------------------------------------
 * Features:
 *  - Git change summary (committers, commits, files changed)
 *  - Diff preview page before deployment
 *  - Helm diff preview
 *  - ConfigMap + Secret manifest diffing
 *  - SAST / SCA / Secret scanning
 *  - Container scanning
 *  - SBOM generation
 *  - Signed artifacts
 *  - Manual approval gate
 *  - Kubernetes deployment
 *  - HTML preview report published in Jenkins
 *
 * Recommended Plugins:
 *  - Pipeline
 *  - HTML Publisher
 *  - Warnings NG
 *  - Kubernetes CLI
 *  - Credentials Binding
 *  - Blue Ocean
 *  - Git
 *
 * Recommended Tools:
 *  - trivy
 *  - syft
 *  - grype
 *  - gitleaks
 *  - helm
 *  - kubectl
 *  - jq
 *  - yq
 */

pipeline {

    agent {
        // Use android-agent (k8s pod) — has kubectl, helm, docker available
        // Switch to 'mobileapp-m3' for Mac Mini or 'ios-agent' for mm1
        label 'android-agent'
    }

    options {
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
    }

    environment {

        APP_NAME            = "payments-api"
        ENVIRONMENT         = "prod"

        REGISTRY            = "registry.company.io"
        IMAGE_NAME          = "${REGISTRY}/${APP_NAME}"

        GIT_PREVIOUS_TAG    = sh(
            script: "git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo NONE",
            returnStdout: true
        ).trim()

        CURRENT_COMMIT      = sh(
            script: "git rev-parse HEAD",
            returnStdout: true
        ).trim()

        SHORT_SHA           = sh(
            script: "git rev-parse --short HEAD",
            returnStdout: true
        ).trim()

        VERSION             = "v${BUILD_NUMBER}-${SHORT_SHA}"

        HELM_RELEASE        = "payments-api"
        HELM_NAMESPACE      = "production"

        HELM_CHART_PATH     = "./helm/payments-api"

        PREVIEW_DIR         = "preview-report"

        DOCKER_BUILDKIT     = "1"
    }

    stages {

        /*
         * ============================================================
         * CHECKOUT
         * ============================================================
         */

        stage('Checkout') {
            steps {
                checkout scm

                sh '''
                    mkdir -p ${PREVIEW_DIR}
                '''
            }
        }

        /*
         * ============================================================
         * BUILD CHANGE PREVIEW
         * ============================================================
         */

        stage('Generate Change Preview') {

            steps {

                sh '''
                    echo "Generating Git change summary..."

                    PREV_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || true)

                    if [ -z "$PREV_TAG" ]; then
                      PREV_TAG=$(git rev-list --max-parents=0 HEAD)
                    fi

                    echo "Previous Reference: $PREV_TAG"

                    git log ${PREV_TAG}..HEAD \
                      --pretty=format:'<tr><td>%h</td><td>%an</td><td>%ad</td><td>%s</td></tr>' \
                      --date=short \
                      > ${PREVIEW_DIR}/commits.html

                    git diff --stat ${PREV_TAG}..HEAD \
                      > ${PREVIEW_DIR}/git-diff-stat.txt

                    git diff ${PREV_TAG}..HEAD \
                      > ${PREVIEW_DIR}/git-full.diff

                    git diff --name-status ${PREV_TAG}..HEAD \
                      > ${PREVIEW_DIR}/changed-files.txt
                '''
            }
        }

        /*
         * ============================================================
         * HELM DIFF PREVIEW
         * ============================================================
         */

        stage('Helm Diff Preview') {

            steps {

                sh '''
                    echo "Generating Helm diff..."

                    helm dependency update ${HELM_CHART_PATH}

                    helm plugin list | grep diff || \
                      helm plugin install https://github.com/databus23/helm-diff

                    helm diff upgrade \
                      ${HELM_RELEASE} \
                      ${HELM_CHART_PATH} \
                      --namespace ${HELM_NAMESPACE} \
                      --allow-unreleased \
                      > ${PREVIEW_DIR}/helm-diff.txt || true
                '''
            }
        }

        /*
         * ============================================================
         * CONFIGMAP + SECRET DIFF
         * ============================================================
         */

        stage('Config & Secret Diff') {

            steps {

                sh '''
                    mkdir -p rendered

                    echo "Rendering manifests..."

                    helm template \
                      ${HELM_RELEASE} \
                      ${HELM_CHART_PATH} \
                      --namespace ${HELM_NAMESPACE} \
                      > rendered/all.yaml

                    echo "Extracting ConfigMaps..."
                    yq eval 'select(.kind == "ConfigMap")' rendered/all.yaml \
                      > rendered/configmaps.yaml || true

                    echo "Extracting Secrets..."
                    yq eval 'select(.kind == "Secret")' rendered/all.yaml \
                      > rendered/secrets.yaml || true

                    echo "Fetching live cluster manifests..."

                    kubectl get configmaps \
                      -n ${HELM_NAMESPACE} \
                      -o yaml \
                      > rendered/live-configmaps.yaml || true

                    kubectl get secrets \
                      -n ${HELM_NAMESPACE} \
                      -o yaml \
                      > rendered/live-secrets.yaml || true

                    diff -u rendered/live-configmaps.yaml rendered/configmaps.yaml \
                      > ${PREVIEW_DIR}/configmap-diff.txt || true

                    diff -u rendered/live-secrets.yaml rendered/secrets.yaml \
                      > ${PREVIEW_DIR}/secret-diff.txt || true
                '''
            }
        }

        /*
         * ============================================================
         * SECRET SCANNING
         * ============================================================
         */

        stage('Secret Scan') {

            steps {

                sh '''
                    echo "Running Gitleaks..."

                    gitleaks detect \
                      --source . \
                      --report-format sarif \
                      --report-path gitleaks.sarif
                '''
            }
        }

        /*
         * ============================================================
         * SAST
         * ============================================================
         */

        stage('Static Analysis') {

            parallel {

                stage('Semgrep') {
                    steps {
                        sh '''
                            semgrep scan \
                              --config auto \
                              --json \
                              --output semgrep.json
                        '''
                    }
                }

                stage('SonarQube') {
                    steps {
                        sh '''
                            sonar-scanner
                        '''
                    }
                }
            }
        }

        /*
         * ============================================================
         * BUILD CONTAINER
         * ============================================================
         */

        stage('Build Image') {

            steps {

                sh '''
                    docker build \
                      -t ${IMAGE_NAME}:${VERSION} \
                      .
                '''
            }
        }

        /*
         * ============================================================
         * SBOM
         * ============================================================
         */

        stage('Generate SBOM') {

            steps {

                sh '''
                    syft ${IMAGE_NAME}:${VERSION} \
                      -o cyclonedx-json \
                      > sbom.json
                '''
            }
        }

        /*
         * ============================================================
         * CONTAINER VULN SCAN
         * ============================================================
         */

        stage('Container Scan') {

            steps {

                sh '''
                    trivy image \
                      --exit-code 1 \
                      --severity CRITICAL,HIGH \
                      ${IMAGE_NAME}:${VERSION}
                '''
            }
        }

        /*
         * ============================================================
         * SIGN IMAGE
         * ============================================================
         */

        stage('Sign Container') {

            steps {

                withCredentials([
                    file(
                        credentialsId: 'cosign-key',
                        variable: 'COSIGN_KEY'
                    )
                ]) {

                    sh '''
                        cosign sign \
                          --key ${COSIGN_KEY} \
                          ${IMAGE_NAME}:${VERSION}
                    '''
                }
            }
        }

        /*
         * ============================================================
         * PUBLISH PREVIEW REPORT
         * ============================================================
         */

        stage('Publish Preview Dashboard') {

            steps {

                sh '''
                    cat <<EOF > ${PREVIEW_DIR}/index.html

                    <html>
                    <head>
                        <title>Release Preview</title>

                        <style>
                            body {
                                font-family: Arial;
                                margin: 20px;
                                background: #f4f4f4;
                            }

                            h1,h2 {
                                color: #333;
                            }

                            pre {
                                background: white;
                                padding: 15px;
                                border-radius: 6px;
                                overflow-x: auto;
                            }

                            table {
                                border-collapse: collapse;
                                width: 100%;
                                background: white;
                            }

                            td, th {
                                border: 1px solid #ddd;
                                padding: 8px;
                            }

                            th {
                                background: #222;
                                color: white;
                            }
                        </style>

                    </head>

                    <body>

                    <h1>Release Preview Dashboard</h1>

                    <h2>Version</h2>
                    <p>${VERSION}</p>

                    <h2>Git Commits</h2>

                    <table>
                    <tr>
                        <th>Commit</th>
                        <th>Author</th>
                        <th>Date</th>
                        <th>Message</th>
                    </tr>

                    $(cat ${PREVIEW_DIR}/commits.html)

                    </table>

                    <h2>Changed Files</h2>
                    <pre>$(cat ${PREVIEW_DIR}/changed-files.txt)</pre>

                    <h2>Git Diff Stat</h2>
                    <pre>$(cat ${PREVIEW_DIR}/git-diff-stat.txt)</pre>

                    <h2>Helm Diff</h2>
                    <pre>$(cat ${PREVIEW_DIR}/helm-diff.txt)</pre>

                    <h2>ConfigMap Diff</h2>
                    <pre>$(cat ${PREVIEW_DIR}/configmap-diff.txt)</pre>

                    <h2>Secret Diff</h2>
                    <pre>$(cat ${PREVIEW_DIR}/secret-diff.txt)</pre>

                    </body>
                    </html>

                    EOF
                '''

                publishHTML([
                    allowMissing         : false,
                    alwaysLinkToLastBuild: true,
                    keepAll              : true,
                    reportDir            : "${PREVIEW_DIR}",
                    reportFiles          : 'index.html',
                    reportName           : 'Release Preview Dashboard'
                ])
            }
        }

        /*
         * ============================================================
         * MANUAL APPROVAL
         * ============================================================
         */

        stage('Approval Gate') {

            steps {

                timeout(time: 2, unit: 'HOURS') {

                    input(
                        message: """
                        Review:
                        - Git Changes
                        - Helm Diff
                        - ConfigMap Diff
                        - Secret Diff
                        - Vulnerability Reports

                        Proceed to production?
                        """,
                        ok: "Deploy"
                    )
                }
            }
        }

        /*
         * ============================================================
         * PUSH IMAGE
         * ============================================================
         */

        stage('Push Image') {

            steps {

                withCredentials([
                    usernamePassword(
                        credentialsId: 'docker-registry',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )
                ]) {

                    sh '''
                        echo "${DOCKER_PASS}" | docker login \
                          ${REGISTRY} \
                          -u "${DOCKER_USER}" \
                          --password-stdin

                        docker push ${IMAGE_NAME}:${VERSION}
                    '''
                }
            }
        }

        /*
         * ============================================================
         * DEPLOY
         * ============================================================
         */

        stage('Deploy') {

            steps {

                sh '''
                    helm upgrade \
                      --install \
                      ${HELM_RELEASE} \
                      ${HELM_CHART_PATH} \
                      --namespace ${HELM_NAMESPACE} \
                      --set image.tag=${VERSION} \
                      --wait \
                      --timeout 15m
                '''
            }
        }

        /*
         * ============================================================
         * POST DEPLOY VALIDATION
         * ============================================================
         */

        stage('Post Deploy Validation') {

            steps {

                sh '''
                    kubectl rollout status deployment/${APP_NAME} \
                      -n ${HELM_NAMESPACE}

                    kubectl get pods -n ${HELM_NAMESPACE}
                '''
            }
        }
    }

    post {

        always {

            archiveArtifacts artifacts: '''
                preview-report/**,
                sbom.json,
                semgrep.json,
                gitleaks.sarif
            ''', fingerprint: true
        }

        success {

            echo "Deployment successful."
        }

        failure {

            echo "Pipeline failed."
        }
    }
}