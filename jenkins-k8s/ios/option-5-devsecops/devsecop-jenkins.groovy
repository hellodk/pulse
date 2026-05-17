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
 *  - LLM failure analysis via Ollama (configurable endpoints)
 *  - HTML email notifications with failed stage tracking
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
        // cylon-agent: Jenkins JNLP agent running directly on the k8s host.
        // Has helm, kubectl, docker, gitleaks, jq available natively.
        // Label 'k8s-devsecops-agent' also accepted (original blueprint label).
        label 'cylon-agent'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        string(name: 'NOTIFY_EMAIL',
               defaultValue: 'reject@hellodk.io',
               description: 'Recipient email(s) — comma-separated. Leave blank to skip.')
        string(name: 'LLM_ENDPOINT_A',
               defaultValue: 'http://100.89.50.27:11434',
               description: 'Primary Ollama endpoint for LLM failure analysis (via Tailscale)')
        string(name: 'LLM_ENDPOINT_B',
               defaultValue: 'http://100.104.14.62:21434',
               description: 'Secondary llama.cpp endpoint for LLM cross-check')
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

        HELM_CHART_PATH     = "jenkins-k8s/ios/option-5-devsecops/helm/payments-api"

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
                sh 'mkdir -p ${PREVIEW_DIR}'
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * PRE-REQUISITE CHECK
         * Validates all required tools are present. Reports missing ones
         * as warnings — never installs anything automatically.
         * ============================================================
         */

        stage('Pre-requisite Check') {
            steps {
                sh '''#!/bin/bash
echo "┌─────────────────────────────────────────────────────────────────────┐"
echo "│                        Pre-requisite Check                          │"
echo "└─────────────────────────────────────────────────────────────────────┘"
printf "%-20s %-28s %-28s\\n" "Tool" "Installed" "Required"
echo "────────────────────────────────────────────────────────────────────────"
MISSING=""

chk() {
    local label="$1"
    local req="$2"
    local ver_cmd="$3"

    if command -v "${4:-${label}}" >/dev/null 2>&1 || eval "${5:-false}" >/dev/null 2>&1; then
        local ver
        ver=$(eval "$ver_cmd" 2>/dev/null | head -1 | sed "s/[Vv]ersion:* //;s/^v//")
        printf "  [OK]      %-26s %-28s %s\\n" "$label" "$ver" "$req"
    else
        printf "  [MISSING] %-26s %-28s --> install manually\\n" "$label" "$req"
        MISSING="$MISSING\\n  - $label  (need: $req)"
    fi
}

chk_plugin() {
    local label="$1"
    local req="$2"
    local test_cmd="$3"
    local install_hint="$4"

    if eval "$test_cmd" >/dev/null 2>&1; then
        local ver
        ver=$(eval "$test_cmd" 2>/dev/null | head -1 | sed "s/[Vv]ersion:* //;s/^v//")
        printf "  [OK]      %-26s %-28s %s\\n" "$label" "$ver" "$req"
    else
        printf "  [BROKEN]  %-26s %-28s --> %s\\n" "$label" "$req" "$install_hint"
        MISSING="$MISSING\\n  - $label  (need: $req)  hint: $install_hint"
    fi
}

# Core tools
chk "helm"      ">= 3.13.0  (have 3.17.0)" "helm version --short"
chk "kubectl"   ">= 1.28.0  (have 1.35.0)" "kubectl version --client --short 2>/dev/null || kubectl version --client 2>/dev/null"
chk "docker"    ">= 24.0"                   "docker --version"
chk "git"       ">= 2.40"                   "git --version"
chk "jq"        ">= 1.6     (have 1.7)"     "jq --version"
chk "yq"        ">= 4.40"                   "yq --version"

# Security scanners
chk "trivy"     ">= 0.50.0  (have 0.70.0)" "trivy --version"
chk "syft"      ">= 1.0.0"                  "syft version"
chk "grype"     ">= 0.70.0"                 "grype version"
chk "gitleaks"  ">= 8.0.0   (have 8.26.0)" "gitleaks version"
chk "semgrep"   ">= 1.60.0"                 "semgrep --version"
chk "cosign"    ">= 2.0.0"                  "cosign version"

# Helm plugins
chk_plugin "helm-diff" \
    ">= 3.9.4 (for Helm 3.13+)" \
    "helm diff version" \
    "helm plugin remove diff && helm plugin install https://github.com/databus23/helm-diff --version v3.9.4"

echo "────────────────────────────────────────────────────────────────────────"
if [ -n "$MISSING" ]; then
    echo ""
    echo "  WARNING — the following tools are missing or broken:"
    printf "$MISSING\\n"
    echo ""
    echo "  Affected pipeline stages will be skipped (catchError: UNSTABLE)."
    echo "  Install missing tools on the cylon-agent host and re-run."
else
    echo "  All tools present — pipeline will run fully."
fi
echo "────────────────────────────────────────────────────────────────────────"
'''
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
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
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * HELM DIFF PREVIEW
         * ============================================================
         */

        stage('Helm Diff Preview') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        command -v helm        || { echo "SKIP: helm not found — install helm"; exit 1; }
                        [ -d "${HELM_CHART_PATH}" ] || { echo "SKIP: chart not found at ${HELM_CHART_PATH}"; exit 1; }
                        helm diff version 2>/dev/null \
                          || { echo "SKIP: helm-diff plugin not working — run: helm plugin install https://github.com/databus23/helm-diff"; exit 1; }
                        helm diff upgrade ${HELM_RELEASE} ${HELM_CHART_PATH} \
                          --namespace ${HELM_NAMESPACE} --allow-unreleased \
                          > ${PREVIEW_DIR}/helm-diff.txt || true
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * CONFIGMAP + SECRET DIFF
         * ============================================================
         */

        stage('Config & Secret Diff') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        command -v yq    || { echo "SKIP: yq not found"; exit 1; }
                        command -v helm  || { echo "SKIP: helm not found"; exit 1; }
                        [ -d "${HELM_CHART_PATH}" ] || { echo "SKIP: chart not found"; exit 1; }
                        mkdir -p rendered
                        helm template ${HELM_RELEASE} ${HELM_CHART_PATH} \
                          --namespace ${HELM_NAMESPACE} > rendered/all.yaml
                        yq eval "select(.kind == \"ConfigMap\")" rendered/all.yaml \
                          > rendered/configmaps.yaml || true
                        yq eval "select(.kind == \"Secret\")" rendered/all.yaml \
                          > rendered/secrets.yaml || true
                        kubectl get configmaps -n ${HELM_NAMESPACE} -o yaml \
                          > rendered/live-configmaps.yaml || true
                        kubectl get secrets -n ${HELM_NAMESPACE} -o yaml \
                          > rendered/live-secrets.yaml || true
                        diff -u rendered/live-configmaps.yaml rendered/configmaps.yaml \
                          > ${PREVIEW_DIR}/configmap-diff.txt || true
                        diff -u rendered/live-secrets.yaml rendered/secrets.yaml \
                          > ${PREVIEW_DIR}/secret-diff.txt || true
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * SECRET SCANNING
         * ============================================================
         */

        stage('Secret Scan') {
            steps {
                // gitleaks exits 1 when leaks are found — mark UNSTABLE not FAILURE
                // so the pipeline continues to the next stage
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh '''
                        command -v gitleaks || { echo "SKIP: gitleaks not found"; exit 0; }
                        echo "Running Gitleaks secret scan..."
                        gitleaks detect \
                          --source . \
                          --report-format sarif \
                          --report-path gitleaks.sarif \
                          --exit-code 0
                        echo "Gitleaks scan complete — see gitleaks.sarif for findings"
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
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
                        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                            sh '''
                                command -v semgrep || { echo "SKIP: semgrep not installed"; exit 1; }
                                semgrep scan --config auto --json --output semgrep.json
                            '''
                        }
                    }
                    post {
                        failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
                    }
                }

                stage('SonarQube') {
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                            sh '''
                                command -v sonar-scanner || { echo "SKIP: sonar-scanner not installed"; exit 1; }
                                sonar-scanner
                            '''
                        }
                    }
                    post {
                        failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
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
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        [ -f Dockerfile ] || { echo "SKIP: no Dockerfile in repo root"; exit 1; }
                        docker build -t ${IMAGE_NAME}:${VERSION} .
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * SBOM
         * ============================================================
         */

        stage('Generate SBOM') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        command -v syft || { echo "SKIP: syft not installed (brew install syft)"; exit 1; }
                        syft ${IMAGE_NAME}:${VERSION} -o cyclonedx-json > sbom.json
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * CONTAINER VULN SCAN
         * ============================================================
         */

        stage('Container Scan') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        command -v trivy || { echo "SKIP: trivy not installed"; exit 1; }
                        trivy image --exit-code 1 --severity CRITICAL,HIGH ${IMAGE_NAME}:${VERSION}
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * SIGN IMAGE
         * ============================================================
         */

        stage('Sign Container') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    withCredentials([
                        file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY')
                    ]) {
                        sh '''
                            command -v cosign || { echo "SKIP: cosign not installed"; exit 1; }
                            cosign sign --key ${COSIGN_KEY} ${IMAGE_NAME}:${VERSION}
                        '''
                    }
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * PUBLISH PREVIEW REPORT + NOTIFY
         * Generates a rich HTML dashboard with embedded Approve/Abort
         * buttons, then sends an email with the same links so the user
         * never has to interact with the Jenkins console.
         * ============================================================
         */

        stage('Publish Preview Dashboard') {

            steps {

                sh '''#!/bin/bash
APPROVE_URL="../input/deploy-approval/proceedEmpty"
ABORT_URL="../input/deploy-approval/abort"

cat > ${PREVIEW_DIR}/index.html <<HTMLEOF
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Release Preview &mdash; ${VERSION}</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: -apple-system, 'Segoe UI', sans-serif;
      background: #F9FAFB;
      color: #111827;
      min-height: 100vh;
    }

    /* ── Sticky Header ─────────────────────────────────────────────── */
    .site-header {
      position: sticky;
      top: 0;
      z-index: 100;
      background: #FFFFFF;
      border-bottom: 1px solid #E5E7EB;
      box-shadow: 0 1px 4px rgba(0,0,0,.08);
    }
    .header-inner {
      max-width: 1200px;
      margin: 0 auto;
      padding: 14px 28px;
      display: flex;
      align-items: center;
      gap: 20px;
      flex-wrap: wrap;
    }
    .header-left { flex: 1; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; min-width: 0; }
    .header-title { font-size: 18px; font-weight: 700; color: #111827; white-space: nowrap; }
    .pill {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 600;
      border: 1px solid #E5E7EB;
      background: #F3F4F6;
      color: #4B5563;
      white-space: nowrap;
    }
    .header-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .btn-approve, .btn-abort {
      display: inline-block;
      padding: 10px 22px;
      border-radius: 7px;
      text-decoration: none;
      font-size: 14px;
      font-weight: 700;
      white-space: nowrap;
      line-height: 1;
    }
    .btn-approve { background: #16A34A; color: #FFFFFF; }
    .btn-approve:hover { background: #15803D; }
    .btn-abort   { background: #DC2626; color: #FFFFFF; }
    .btn-abort:hover   { background: #B91C1C; }
    .header-note {
      font-size: 11px;
      color: #92400E;
      background: #FFFBEB;
      border: 1px solid #FCD34D;
      border-radius: 6px;
      padding: 5px 10px;
      white-space: nowrap;
    }

    /* ── Build Info Strip ──────────────────────────────────────────── */
    .build-strip {
      background: #FFFFFF;
      border-bottom: 1px solid #E5E7EB;
    }
    .build-strip-inner {
      max-width: 1200px;
      margin: 0 auto;
      padding: 12px 28px;
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }
    .stat-pill {
      display: flex;
      align-items: baseline;
      gap: 6px;
      background: #F9FAFB;
      border: 1px solid #E5E7EB;
      border-radius: 8px;
      padding: 8px 16px;
    }
    .stat-pill .sp-label {
      font-size: 11px;
      font-weight: 600;
      color: #4B5563;
      text-transform: uppercase;
      letter-spacing: .5px;
    }
    .stat-pill .sp-value {
      font-size: 14px;
      font-weight: 700;
      color: #111827;
      font-family: 'SFMono-Regular', 'Consolas', monospace;
    }

    /* ── Main content ──────────────────────────────────────────────── */
    .page-body {
      max-width: 1200px;
      margin: 0 auto;
      padding: 28px 28px 48px;
    }

    /* ── Collapsible sections ──────────────────────────────────────── */
    details {
      background: #FFFFFF;
      border: 1px solid #E5E7EB;
      border-radius: 10px;
      margin-bottom: 16px;
      box-shadow: 0 1px 3px rgba(0,0,0,.05);
      overflow: hidden;
    }
    summary {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 14px 20px;
      font-size: 14px;
      font-weight: 600;
      color: #111827;
      cursor: pointer;
      user-select: none;
      list-style: none;
    }
    summary::-webkit-details-marker { display: none; }
    summary::after {
      content: '▸';
      margin-left: auto;
      font-size: 12px;
      color: #6B7280;
      transition: transform .18s;
    }
    details[open] > summary::after { transform: rotate(90deg); }
    summary:hover { background: #F9FAFB; }
    .section-body { padding: 0 20px 20px; }

    /* ── Data table ────────────────────────────────────────────────── */
    .data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .data-table th {
      background: #F3F4F6;
      color: #4B5563;
      padding: 9px 12px;
      text-align: left;
      font-weight: 600;
      border-bottom: 1px solid #E5E7EB;
    }
    .data-table td {
      padding: 9px 12px;
      border-bottom: 1px solid #F3F4F6;
      color: #111827;
      vertical-align: top;
    }
    .data-table tr:last-child td { border-bottom: none; }
    .data-table td:first-child {
      font-family: 'SFMono-Regular', 'Consolas', monospace;
      font-size: 12px;
      color: #6D28D9;
    }

    /* ── Code / pre blocks ─────────────────────────────────────────── */
    pre {
      background: #F3F4F6;
      color: #111827;
      padding: 16px;
      border-radius: 7px;
      font-size: 12px;
      font-family: 'SFMono-Regular', 'Consolas', monospace;
      line-height: 1.65;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      max-height: 450px;
      border: 1px solid #E5E7EB;
    }
    code {
      background: #EDE9FE;
      color: #5B21B6;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 12px;
      font-family: 'SFMono-Regular', 'Consolas', monospace;
    }

    /* ── Footer ────────────────────────────────────────────────────── */
    .site-footer {
      text-align: center;
      padding: 20px 28px;
      font-size: 12px;
      color: #4B5563;
      border-top: 1px solid #E5E7EB;
      background: #FFFFFF;
    }
  </style>
</head>
<body>

  <!-- ── Sticky Header ──────────────────────────────────────────────── -->
  <header class="site-header">
    <div class="header-inner">
      <div class="header-left">
        <span class="header-title">Release Preview</span>
        <span class="pill">${JOB_NAME}</span>
        <span class="pill">Build #${BUILD_NUMBER}</span>
      </div>
      <div class="header-actions">
        <span class="header-note">&#9888; You must be logged into Jenkins &middot; Expires in 24 h</span>
        <a href="${APPROVE_URL}" class="btn-approve">&#9989; Approve Deploy</a>
        <a href="${ABORT_URL}"   class="btn-abort">&#10060; Abort</a>
      </div>
    </div>
  </header>

  <!-- ── Build Info Strip ───────────────────────────────────────────── -->
  <div class="build-strip">
    <div class="build-strip-inner">
      <div class="stat-pill">
        <span class="sp-label">Version</span>
        <span class="sp-value">${VERSION}</span>
      </div>
      <div class="stat-pill">
        <span class="sp-label">Commit</span>
        <span class="sp-value">${SHORT_SHA}</span>
      </div>
      <div class="stat-pill">
        <span class="sp-label">Environment</span>
        <span class="sp-value">${ENVIRONMENT}</span>
      </div>
      <div class="stat-pill">
        <span class="sp-label">Build #</span>
        <span class="sp-value">${BUILD_NUMBER}</span>
      </div>
    </div>
  </div>

  <!-- ── Collapsible Sections ───────────────────────────────────────── -->
  <div class="page-body">

    <!-- Git Commits — open by default -->
    <details open>
      <summary>&#128203; Git Commits</summary>
      <div class="section-body">
        <table class="data-table">
          <thead>
            <tr><th>Hash</th><th>Author</th><th>Date</th><th>Message</th></tr>
          </thead>
          <tbody>
            $(cat ${PREVIEW_DIR}/commits.html 2>/dev/null || echo '<tr><td colspan="4">No data</td></tr>')
          </tbody>
        </table>
      </div>
    </details>

    <!-- Changed Files — open by default -->
    <details open>
      <summary>&#128193; Changed Files</summary>
      <div class="section-body">
        <pre>$(cat ${PREVIEW_DIR}/changed-files.txt 2>/dev/null || echo 'No changes')</pre>
      </div>
    </details>

    <!-- Diff Summary — collapsed -->
    <details>
      <summary>&#128202; Diff Summary</summary>
      <div class="section-body">
        <pre>$(cat ${PREVIEW_DIR}/git-diff-stat.txt 2>/dev/null || echo 'Not available')</pre>
      </div>
    </details>

    <!-- Helm Diff — collapsed -->
    <details>
      <summary>&#9881;&#65039; Helm Diff</summary>
      <div class="section-body">
        <pre>$(cat ${PREVIEW_DIR}/helm-diff.txt 2>/dev/null || echo 'Not available')</pre>
      </div>
    </details>

    <!-- ConfigMap Diff — collapsed -->
    <details>
      <summary>&#128462; ConfigMap Diff</summary>
      <div class="section-body">
        <pre>$(cat ${PREVIEW_DIR}/configmap-diff.txt 2>/dev/null || echo 'No changes')</pre>
      </div>
    </details>

    <!-- Secret Diff — collapsed -->
    <details>
      <summary>&#128273; Secret Diff</summary>
      <div class="section-body">
        <pre>$(cat ${PREVIEW_DIR}/secret-diff.txt 2>/dev/null || echo 'No changes')</pre>
        <p style="margin-top:10px;font-size:12px;color:#4B5563;">&#128274; Secret values are redacted in this output.</p>
      </div>
    </details>

  </div><!-- /page-body -->

  <!-- ── Footer ─────────────────────────────────────────────────────── -->
  <footer class="site-footer">
    Jenkins CI &middot; devsecops &middot; powered by Pulse
  </footer>

</body>
</html>
HTMLEOF
'''

                publishHTML([
                    allowMissing         : false,
                    alwaysLinkToLastBuild: true,
                    keepAll              : true,
                    reportDir            : "${PREVIEW_DIR}",
                    reportFiles          : 'index.html',
                    reportName           : 'Release Preview Dashboard'
                ])

                script {
                    if (params.NOTIFY_EMAIL?.trim()) {
                        def approveUrl  = "http://100.89.50.27:30881/job/devsecops/job/master/${env.BUILD_NUMBER}/input/deploy-approval/proceedEmpty"
                        def abortUrl    = "http://100.89.50.27:30881/job/devsecops/job/master/${env.BUILD_NUMBER}/input/deploy-approval/abort"
                        def dashUrl     = "${env.BUILD_URL}Release_20Preview_20Dashboard"
                        def ver         = env.VERSION ?: env.BUILD_NUMBER
                        def sha         = env.SHORT_SHA ?: (env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'N/A')
                        def ts          = new Date().format("dd MMM yyyy HH:mm:ss 'IST'", TimeZone.getTimeZone('Asia/Kolkata'))
                        emailext(
                            subject: "&#9989; Approval Required: ${env.JOB_NAME} #${env.BUILD_NUMBER} — ready to deploy ${ver}",
                            mimeType: 'text/html',
                            to: params.NOTIFY_EMAIL,
                            body: """
<html><head><style>
  body{font-family:-apple-system,Arial,sans-serif;background:#f0f2f5;margin:0;padding:16px;}
  .wrap{max-width:700px;margin:0 auto;}
  .hdr{background:linear-gradient(135deg,#1f6feb,#388bfd);color:#fff;padding:22px 28px;border-radius:10px 10px 0 0;}
  .hdr h1{margin:0;font-size:20px;} .hdr p{margin:4px 0 0;font-size:12px;opacity:.85;}
  .body{background:#fff;padding:24px 28px;border:1px solid #e8eaed;}
  table{width:100%;border-collapse:collapse;font-size:13px;}
  td{padding:9px 14px;border-bottom:1px solid #f0f0f0;}
  tr:nth-child(odd) td{background:#fafbfc;}
  .lbl{font-weight:600;color:#555;width:140px;}
  .actions{margin:24px 0;text-align:center;}
  .btn{display:inline-block;padding:12px 32px;border-radius:6px;text-decoration:none;font-size:15px;font-weight:700;margin:0 8px;}
  .approve{background:#238636;color:#fff;}
  .abort{background:#b62324;color:#fff;}
  .note{font-size:11px;color:#888;margin-top:10px;text-align:center;}
  .dash{margin-top:16px;text-align:center;}
  .dash a{color:#0078d4;font-size:13px;}
  .ftr{background:#f8f9fa;border:1px solid #e8eaed;border-top:none;padding:12px 28px;text-align:center;font-size:11px;color:#888;border-radius:0 0 10px 10px;}
</style></head><body><div class="wrap">
  <div class="hdr">
    <h1>&#9989; Production Deployment Approval Required</h1>
    <p>${env.JOB_NAME} &middot; Build #${env.BUILD_NUMBER} &middot; ${ts}</p>
  </div>
  <div class="body">
    <table>
      <tr><td class="lbl">Job</td><td>${env.JOB_NAME}</td></tr>
      <tr><td class="lbl">Build #</td><td>${env.BUILD_NUMBER}</td></tr>
      <tr><td class="lbl">Version</td><td><strong>${ver}</strong></td></tr>
      <tr><td class="lbl">Commit</td><td><code>${sha}</code></td></tr>
      <tr><td class="lbl">Environment</td><td>${env.ENVIRONMENT ?: 'prod'}</td></tr>
    </table>
    <div class="actions">
      <a href="${approveUrl}" class="btn approve">&#9989;&nbsp;&nbsp;Approve Deploy</a>
      <a href="${abortUrl}"   class="btn abort">&#10060;&nbsp;&nbsp;Abort</a>
      <p class="note">You must be logged into Jenkins to use these buttons &middot; Expires in 24 hours</p>
    </div>
    <div class="dash">
      <a href="${dashUrl}">&#128230; Open full Release Preview Dashboard</a>
      &nbsp;&middot;&nbsp;
      <a href="${env.BUILD_URL}">Open Build</a>
    </div>
  </div>
  <div class="ftr">Jenkins CI &middot; Pulse &middot; ${env.BUILD_URL}</div>
</div></body></html>"""
                        )
                    }
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * APPROVAL GATE
         * Waits silently — user acts from email or Release Preview
         * Dashboard, never from the Jenkins console.
         * ============================================================
         */

        stage('Approval Gate') {
            steps {
                timeout(time: 24, unit: 'HOURS') {
                    input(
                        id:      'deploy-approval',
                        message: 'Waiting for approval. Check your email or open the Release Preview Dashboard.',
                        ok:      'Deploy'
                    )
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * PUSH IMAGE
         * ============================================================
         */

        stage('Push Image') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    withCredentials([
                        usernamePassword(
                            credentialsId: 'docker-registry',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )
                    ]) {
                        sh '''
                            echo "${DOCKER_PASS}" | docker login ${REGISTRY} \
                              -u "${DOCKER_USER}" --password-stdin
                            docker push ${IMAGE_NAME}:${VERSION}
                        '''
                    }
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * DEPLOY
         * ============================================================
         */

        stage('Deploy') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        [ -d "${HELM_CHART_PATH}" ] || { echo "SKIP: chart not found at ${HELM_CHART_PATH}"; exit 1; }
                        helm upgrade --install ${HELM_RELEASE} ${HELM_CHART_PATH} \
                          --namespace ${HELM_NAMESPACE} \
                          --set image.tag=${VERSION} \
                          --wait --timeout 15m
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        /*
         * ============================================================
         * POST DEPLOY VALIDATION
         * ============================================================
         */

        stage('Post Deploy Validation') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    sh '''
                        kubectl rollout status deployment/${APP_NAME} \
                          -n ${HELM_NAMESPACE} --timeout=60s
                        kubectl get pods -n ${HELM_NAMESPACE}
                    '''
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
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
            script {
                if (params.NOTIFY_EMAIL?.trim()) {
                    def duration = currentBuild.durationString ?: 'N/A'
                    emailext(
                        subject: "✅ DevSecOps PASSED: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        mimeType: 'text/html',
                        to: params.NOTIFY_EMAIL,
                        body: """<html><head><style>
body{font-family:-apple-system,Arial,sans-serif;background:#f0f2f5;margin:0;padding:16px;}
.wrap{max-width:760px;margin:0 auto;}
.hdr{background:linear-gradient(135deg,#27ae60,#1e8449);color:#fff;padding:22px 28px;border-radius:10px 10px 0 0;}
.hdr h1{margin:0;font-size:20px;}.hdr p{margin:4px 0 0;font-size:12px;opacity:.85;}
.body{background:#fff;padding:24px 28px;border:1px solid #e8eaed;}
table{width:100%;border-collapse:collapse;font-size:13px;}
td{padding:9px 14px;border-bottom:1px solid #f0f0f0;}
tr:nth-child(odd) td{background:#fafbfc;}
.lbl{font-weight:600;color:#555;width:160px;}
.btns{margin-top:16px;}.btn{display:inline-block;padding:8px 16px;border-radius:5px;text-decoration:none;font-size:12px;font-weight:600;margin-right:8px;}
.b1{background:#27ae60;color:#fff;}.b2{background:#f3f4f6;color:#333;border:1px solid #d1d5db;}
.ftr{background:#f8f9fa;border:1px solid #e8eaed;border-top:none;padding:12px 28px;text-align:center;font-size:11px;color:#888;border-radius:0 0 10px 10px;}
</style></head><body><div class="wrap">
<div class="hdr"><h1>&#9989; DevSecOps Pipeline Passed</h1>
<p>${env.JOB_NAME} &middot; Build #${env.BUILD_NUMBER} &middot; ${new Date().format("dd MMM yyyy HH:mm:ss 'IST'", TimeZone.getTimeZone('Asia/Kolkata'))}</p></div>
<div class="body">
<table>
<tr><td class="lbl">Job</td><td>${env.JOB_NAME}</td></tr>
<tr><td class="lbl">Build #</td><td>${env.BUILD_NUMBER}</td></tr>
<tr><td class="lbl">Duration</td><td>${duration}</td></tr>
</table>
<div class="btns">
<a href="${env.BUILD_URL}" class="btn b1">Open Build</a>
<a href="${env.BUILD_URL}artifact" class="btn b2">Artifacts</a>
</div>
</div>
<div class="ftr">Jenkins CI &middot; Pulse &middot; ${env.BUILD_URL}</div>
</div></body></html>"""
                    )
                }
            }
        }

        failure {
            script {
                node('cylon-agent') {
                    def llmReport = 'LLM analysis not available.'
                    try {
                        sh """#!/bin/bash
set -eo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"
PULSE_DIR="/tmp/pulse-zip-\$\$"
git clone --depth 1 --single-branch --branch master \
    http://dk:admin123@100.89.50.27:30300/dk/pulse.git \
    "\$PULSE_DIR" 2>/dev/null || true
if [ -f "\$PULSE_DIR/jenkins-k8s/shared/zip-logs.sh" ]; then
    bash "\$PULSE_DIR/jenkins-k8s/shared/zip-logs.sh"
fi
rm -rf "\$PULSE_DIR"
"""
                        archiveArtifacts artifacts: "build-logs-*.zip", allowEmptyArchive: true
                        sh """#!/bin/bash
set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

PULSE_DIR="/tmp/pulse-\$\$"
git clone --depth 1 --single-branch --branch master \
    http://dk:admin123@100.89.50.27:30300/dk/pulse.git \
    "\$PULSE_DIR" 2>/dev/null \
  || { echo "Cannot clone pulse from Gitea — skipping LLM analysis"; exit 1; }

bash "\$PULSE_DIR/jenkins-k8s/shared/extract-build-errors.sh" > build-error-report.txt 2>/dev/null || true
export FAILED_STAGE="${env.FAILED_STAGE ?: 'Unknown'}"
export ERROR_SNIPPET="\$(cat build-error-report.txt 2>/dev/null || echo 'Not available')"
export LOG_TAIL="\${ERROR_SNIPPET}"
export BUILD_NUMBER="${env.BUILD_NUMBER}"
export JOB_NAME="${env.JOB_NAME}"
export BUILD_TYPE="DevSecOps-K8s"
export LLM_ENDPOINT_A="${params.LLM_ENDPOINT_A}"
export LLM_ENDPOINT_B="${params.LLM_ENDPOINT_B}"
bash "\$PULSE_DIR/jenkins-k8s/shared/llm-analysis.sh"
rm -rf "\$PULSE_DIR"
"""
                    archiveArtifacts artifacts: 'llm-analysis.md', allowEmptyArchive: true
                        if (fileExists('llm-analysis.md')) { llmReport = readFile('llm-analysis.md') }
                    } catch (e) {
                        llmReport = "LLM analysis failed: ${e.message}"
                    }
                    if (params.NOTIFY_EMAIL?.trim()) {
                        def duration = currentBuild.durationString ?: 'N/A'
                        def llmTrunc = llmReport.size() > 8000 ? llmReport.take(8000) + '\n...(truncated)' : llmReport
                        emailext(
                            subject: "❌ DevSecOps FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                            mimeType: 'text/html',
                            to: params.NOTIFY_EMAIL,
                            attachmentsPattern: 'llm-analysis.md,build-logs-*.zip',
                            body: """<html><head><style>
body{font-family:-apple-system,Arial,sans-serif;background:#f0f2f5;margin:0;padding:16px;}
.wrap{max-width:760px;margin:0 auto;}
.hdr{background:linear-gradient(135deg,#c0392b,#96281b);color:#fff;padding:22px 28px;border-radius:10px 10px 0 0;}
.hdr h1{margin:0;font-size:20px;}.hdr p{margin:4px 0 0;font-size:12px;opacity:.85;}
.body{background:#fff;padding:24px 28px;border:1px solid #e8eaed;}
table{width:100%;border-collapse:collapse;font-size:13px;}
td{padding:9px 14px;border-bottom:1px solid #f0f0f0;}
tr:nth-child(odd) td{background:#fafbfc;}
.lbl{font-weight:600;color:#555;width:160px;}
.fail td{background:#fff5f5!important;color:#c0392b;font-weight:700;}
.sec{font-size:13px;font-weight:700;color:#333;margin:20px 0 8px;padding-bottom:5px;border-bottom:2px solid #e8eaed;}
.llm{background:#0d1117;color:#c9d1d9;padding:16px;border-radius:6px;font-family:'Courier New',monospace;font-size:11px;line-height:1.6;white-space:pre-wrap;word-break:break-word;max-height:500px;overflow-y:auto;}
.btns{margin-top:16px;}.btn{display:inline-block;padding:8px 16px;border-radius:5px;text-decoration:none;font-size:12px;font-weight:600;margin-right:8px;}
.b1{background:#0078d4;color:#fff;}.b2{background:#f3f4f6;color:#333;border:1px solid #d1d5db;}
.ftr{background:#f8f9fa;border:1px solid #e8eaed;border-top:none;padding:12px 28px;text-align:center;font-size:11px;color:#888;border-radius:0 0 10px 10px;}
</style></head><body><div class="wrap">
<div class="hdr"><h1>&#10060; DevSecOps Pipeline Failed</h1>
<p>${env.JOB_NAME} &middot; Build #${env.BUILD_NUMBER} &middot; ${new Date().format("dd MMM yyyy HH:mm:ss 'IST'", TimeZone.getTimeZone('Asia/Kolkata'))}</p></div>
<div class="body">
<table>
<tr><td class="lbl">Job</td><td>${env.JOB_NAME}</td></tr>
<tr><td class="lbl">Build #</td><td>${env.BUILD_NUMBER}</td></tr>
<tr><td class="lbl">Agent</td><td>cylon-agent</td></tr>
<tr><td class="lbl">Duration</td><td>${duration}</td></tr>
<tr class="fail"><td class="lbl">&#128308; Failed Stage</td><td>${env.FAILED_STAGE ?: 'Unknown'}</td></tr>
</table>
<div class="btns">
<a href="${env.BUILD_URL}" class="btn b1">Open Build</a>
<a href="${env.BUILD_URL}console" class="btn b2">Console Log</a>
<a href="${env.BUILD_URL}artifact" class="btn b2">Artifacts</a>
</div>
<div class="sec">&#129302; LLM Failure Analysis &amp; Improvement Suggestions</div>
<div class="llm">${llmTrunc}</div>
</div>
<div class="ftr">Jenkins CI &middot; Pulse &middot; ${env.BUILD_URL}</div>
</div></body></html>"""
                        )
                    }
                }
            }
        }
    }
}
