// =============================================================================
// Jenkinsfile.groovy — iOS Enterprise Banking App Pipeline
// option-4-enterprise
//
// Supports two modes controlled by DUMMY_SIGNING:
//
//   DUMMY_SIGNING=true  (default)
//     Calls generate-dummy-signing.sh to create a self-signed cert and
//     fake provisioning profile.  No Apple Developer Account needed.
//     Produces a real .ipa via manual packaging (bypasses -exportArchive
//     which requires an Apple-signed profile).  Use for pipeline testing.
//
//   DUMMY_SIGNING=false
//     Uses real enterprise credentials from Jenkins credentials store.
//     Runs xcodebuild -exportArchive with ExportOptions-enterprise.plist.
//     Use in production CI with valid Apple Developer Enterprise certs.
//
// NODE PACKAGES
//   USE_PNPM=true  (default) — uses pnpm for faster installs
//   USE_PNPM=false           — falls back to npm
//
//   pnpm benefits over npm in CI:
//     • Content-addressable store: packages never downloaded twice across runs
//     • Hard links instead of copies: ~60% less disk I/O
//     • Parallel install: ~2–3x faster cold install, ~5x faster warm install
//     • --frozen-lockfile: strict mode equivalent to 'npm ci'
//     • Zero phantom dependencies: Metro bundler gets exactly what's declared
//
//   React Native + pnpm requirement:
//     Add to project root .npmrc:  node-linker=hoisted
//     This tells pnpm to hoist node_modules so Metro bundler can resolve them.
//     Without it, Metro will fail to find packages like react, react-native, etc.
//
// SANDBOX SAFE: no rawBuild, no @NonCPS, no unapproved APIs.
// =============================================================================
pipeline {

    agent none

    // ── Parameters ────────────────────────────────────────────────────────────
    parameters {

        // Build target
        choice(name: 'ENVIRONMENT',
               choices: ['Sit', 'Uat', 'Prod'],
               description: 'Target environment — selects scheme BankNow_<Env> and config file')

        choice(name: 'AGENT',
               choices: ['mobileapp', 'mobileapp2', 'mobileapp3', 'mobileapp4'],
               description: 'Mac Mini agent to build on')

        // Signing mode
        booleanParam(name: 'DUMMY_SIGNING',
                     defaultValue: true,
                     description: '''\
true  — generate self-signed dummy cert (no Apple account needed, pipeline test)
false — use real enterprise credentials from Jenkins credentials store''')

        // Real signing credentials (only used when DUMMY_SIGNING=false)
        string(name:        'CERT_CREDENTIAL_ID',
               defaultValue: 'ios-enterprise-dist-cert-p12',
               description:  '[REAL SIGNING] Jenkins Secret File credential ID for .p12 certificate')
        password(name:       'CERT_PASSWORD',
                 defaultValue: '',
                 description:  '[REAL SIGNING] Passphrase for .p12 — masked in console')
        string(name:        'PROFILE_CREDENTIAL_ID',
               defaultValue: 'ios-enterprise-provision-profile',
               description:  '[REAL SIGNING] Jenkins Secret File credential ID for .mobileprovision')

        // Node package manager
        booleanParam(name: 'USE_PNPM',
                     defaultValue: true,
                     description: '''\
true  — use pnpm (faster: content-addressable store, hard links, parallel fetch)
false — use npm  (fallback if pnpm not available or lockfile not migrated)''')

        // Build options
        booleanParam(name: 'RUN_TESTS',
                     defaultValue: true,
                     description: 'Run unit tests before archiving (adds ~5 min)')
        booleanParam(name: 'CLEAN_BUILD',
                     defaultValue: false,
                     description: 'Clean DerivedData before archive (slower, fixes stale build issues)')

        // Notifications & LLM
        string(name:        'NOTIFY_EMAIL',
               defaultValue: 'reject@hellodk.io',
               description:  'Email for build result notification — comma-separated. Leave blank to skip.')
        string(name: 'LLM_ENDPOINT_A',
               defaultValue: 'http://100.89.50.27:11434',
               description: 'Primary Ollama endpoint for LLM failure analysis (via Tailscale)')
        string(name: 'LLM_ENDPOINT_B',
               defaultValue: 'http://100.104.14.62:21434',
               description: 'Secondary llama.cpp endpoint for LLM cross-check')
    }

    options {
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
    }

    environment {
        ENV_LOWER  = params.ENVIRONMENT.toLowerCase()
        APP_NAME     = 'BankNow'
        BUNDLE_ID    = 'com.yourbank.banknow'
        XC_WORKSPACE = 'BankNow.xcworkspace'   // xcodebuild -workspace flag; NOT the Jenkins workspace dir
        SCHEME       = "BankNow_${params.ENVIRONMENT}"
        // Keychain name must match generate-dummy-signing.sh config
        DUMMY_KEYCHAIN = 'ios-banknow-dummy.keychain'
    }

    stages {

        // ── Stage 1: Node Dependencies ────────────────────────────────────────
        // pnpm is the preferred package manager in CI.
        // Falls back to npm if USE_PNPM=false or pnpm unavailable.
        //
        // pnpm setup for React Native (one-time repo change):
        //   1. npm install -g pnpm
        //   2. pnpm import                    (converts package-lock.json → pnpm-lock.yaml)
        //   3. echo "node-linker=hoisted" >> .npmrc   (Metro bundler compatibility)
        //   4. Commit pnpm-lock.yaml + .npmrc
        stage('Node Dependencies') {
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                // Checkout the BankNow app — clone it to the Mac Mini first:
                //   git clone <repo> /Users/dk/jenkins-agent/git/BankNow
                git url: 'file:///Users/dk/jenkins-agent/git/BankNow', branch: 'main'
                sh """#!/bin/bash
                set -euo pipefail
                export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"
                # workspace root IS the app root after git checkout above

                mkdir -p "\${WORKSPACE}/build"

                if ${params.USE_PNPM}; then
                    echo "── Package Manager: pnpm ────────────────────────────────"

                    if ! command -v pnpm &>/dev/null; then
                        echo "pnpm not found — installing globally via npm..."
                        npm install -g pnpm
                    fi
                    echo "pnpm version: \$(pnpm --version)"

                    if ! grep -q "node-linker=hoisted" .npmrc 2>/dev/null; then
                        echo "node-linker=hoisted" >> .npmrc
                        echo "shamefully-hoist=true" >> .npmrc
                    fi

                    # Save output for LLM — pnpm errors are easy to extract from its output
                    pnpm install \\
                        --frozen-lockfile \\
                        --prefer-offline \\
                        --reporter=append-only 2>&1 | tee "\${WORKSPACE}/build/node-install.log"

                else
                    echo "── Package Manager: npm ─────────────────────────────────"
                    echo "npm version: \$(npm --version)"
                    npm install \\
                        --prefer-offline \\
                        --no-audit \\
                        --no-fund \\
                        --no-progress 2>&1 | tee "\${WORKSPACE}/build/node-install.log"
                fi

                echo "── Applying environment config ───────────────────────────"
                npm run ${env.ENV_LOWER}_env
                """
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        // ── Stage 2: CocoaPods ────────────────────────────────────────────────
        // pod install is intentionally kept in the Jenkinsfile (not the shell
        // script) so that dependency installation is visible in the build log
        // and can be cached via the agent's CocoaPods cache dir.
        stage('CocoaPods') {
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                sh """#!/bin/bash
                set -euo pipefail
                export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"
                export LANG=en_US.UTF-8
                export COCOAPODS_DISABLE_STATS=1
                mkdir -p "\${WORKSPACE}/build"

                echo "pod version: \$(pod --version)"
                cd ios

                # Save pod install output for LLM analysis — pod errors are
                # often buried in verbose resolver output
                pod install 2>&1 | tee "\${WORKSPACE}/build/pod-install.log"

                echo "CocoaPods install complete."
                """
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        // ── Stage 3: Setup Signing ────────────────────────────────────────────
        // DUMMY_SIGNING=true  → run generate-dummy-signing.sh
        // DUMMY_SIGNING=false → fetch real cert + profile from Jenkins credentials
        stage('Setup Signing') {
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                script {
                    if (params.DUMMY_SIGNING) {

                        // ── Dummy path (no Apple account) ─────────────────────
                        echo "DUMMY_SIGNING=true — generating self-signed cert..."

                        sh """#!/bin/bash
                        set -euo pipefail
                        export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

                        PULSE_DIR=""
                        for P in "\${HOME:-/Users/dk}/Documents/git/pulse" "/home/dk/Documents/git/pulse"; do
                            [ -f "\$P/jenkins-k8s/ios/option-4-enterprise/generate-dummy-signing.sh" ] && PULSE_DIR="\$P" && break
                        done
                        [ -z "\$PULSE_DIR" ] && echo "ERROR: cannot find generate-dummy-signing.sh — clone pulse repo to ~/Documents/git/pulse on this agent" && exit 1
                        chmod +x "\$PULSE_DIR/jenkins-k8s/ios/option-4-enterprise/generate-dummy-signing.sh"
                        "\$PULSE_DIR/jenkins-k8s/ios/option-4-enterprise/generate-dummy-signing.sh"
                        """

                    } else {

                        // ── Real signing path (production) ────────────────────
                        // Requires: CERT_CREDENTIAL_ID, CERT_PASSWORD, PROFILE_CREDENTIAL_ID
                        echo "DUMMY_SIGNING=false — using real enterprise credentials..."

                        withCredentials([
                            file(credentialsId: params.CERT_CREDENTIAL_ID,      variable: 'CERT_FILE'),
                            file(credentialsId: params.PROFILE_CREDENTIAL_ID,   variable: 'PROV_PROFILE')
                        ]) {
                            sh """#!/bin/bash
                            set -euo pipefail
                            export PATH="/usr/bin:/bin:\${PATH:-}"

                            KEYCHAIN="ios-banknow-real-\${BUILD_NUMBER}.keychain"
                            echo "\${KEYCHAIN}" > /tmp/real_keychain_name_\${BUILD_NUMBER}

                            # Per-build keychain (Tahoe fix)
                            KC_PASS="\$(openssl rand -hex 16)"
                            security create-keychain -p "\${KC_PASS}" "\${KEYCHAIN}"
                            security list-keychains -d user -s "\${KEYCHAIN}" login.keychain
                            security default-keychain -s "\${KEYCHAIN}"
                            security unlock-keychain  -p "\${KC_PASS}" "\${KEYCHAIN}"
                            security set-keychain-settings -lut 7200 "\${KEYCHAIN}"

                            # Write cert pass to tempfile (masking preserved)
                            PASS_FILE="\$(mktemp "\${TMPDIR:-/tmp}/certpass.XXXXXX")"
                            chmod 600 "\${PASS_FILE}"
                            trap "rm -f '\${PASS_FILE}'" EXIT
                            printf '%s' '${params.CERT_PASSWORD}' > "\${PASS_FILE}"

                            security import "\${CERT_FILE}" \\
                                -k "\${KEYCHAIN}" \\
                                -P "\$(cat "\${PASS_FILE}")" \\
                                -T /usr/bin/codesign \\
                                -T /usr/bin/productbuild \\
                                -f pkcs12

                            # Tahoe: grant codesign partition access
                            security set-key-partition-list \\
                                -S apple-tool:,apple:,codesign: \\
                                -s -k "\${KC_PASS}" "\${KEYCHAIN}"

                            # Install provisioning profile
                            PROFILE_UUID="\$(security cms -D -i "\${PROV_PROFILE}" \\
                                | plutil -extract UUID raw - 2>/dev/null)"
                            mkdir -p "\$HOME/Library/MobileDevice/Provisioning Profiles"
                            cp "\${PROV_PROFILE}" \\
                               "\$HOME/Library/MobileDevice/Provisioning Profiles/\${PROFILE_UUID}.mobileprovision"

                            echo "Real signing setup complete — keychain: \${KEYCHAIN}"
                            security find-identity -v -p codesigning "\${KEYCHAIN}"
                            """
                        }
                    }
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        // ── Stage 4: Unit Tests (optional) ────────────────────────────────────
        stage('Unit Tests') {
            when {
                expression { params.RUN_TESTS }
            }
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                sh """#!/bin/bash
                set -euo pipefail
                export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"
                export LANG=en_US.UTF-8

                cd ios

                xcodebuild test \\
                    -workspace "${env.WORKSPACE}" \\
                    -scheme    "${env.SCHEME}" \\
                    -destination 'platform=iOS Simulator,name=iPhone 16,OS=latest' \\
                    -configuration Debug \\
                    CODE_SIGN_IDENTITY="" \\
                    CODE_SIGNING_REQUIRED=NO \\
                    CODE_SIGNING_ALLOWED=NO \\
                    | xcpretty --report junit --output test-results.xml
                """
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'ios/test-results.xml'
                }
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        // ── Stage 5: Archive ──────────────────────────────────────────────────
        stage('Archive') {
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                script {
                    def keychainFlag = params.DUMMY_SIGNING
                        ? "OTHER_CODE_SIGN_FLAGS=\"--keychain ${env.DUMMY_KEYCHAIN}\""
                        : "OTHER_CODE_SIGN_FLAGS=\"--keychain ios-banknow-real-${env.BUILD_NUMBER}.keychain\""

                    def certIdentity = params.DUMMY_SIGNING
                        ? "iPhone Distribution: YourBank Ltd (DUMTEAM01)"
                        : "iPhone Distribution"

                    sh """#!/bin/bash
                    set -euo pipefail
                    export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"
                    export LANG=en_US.UTF-8

                    cd ios

                    if ${params.CLEAN_BUILD}; then
                        echo "Cleaning DerivedData..."
                        rm -rf "\${HOME}/Library/Developer/Xcode/DerivedData/${env.APP_NAME}-"*
                    fi

                    echo "── xcodebuild archive ───────────────────────────────────"
                    echo "  Workspace : ${env.XC_WORKSPACE}"
                    echo "  Scheme    : ${env.SCHEME}"
                    echo "  Identity  : ${certIdentity}"
                    echo "  Dummy     : ${params.DUMMY_SIGNING}"

                    # Save BOTH raw and xcpretty-filtered output.
                    # raw:    100k+ lines — mined by extract-build-errors.sh
                    # pretty: 50-500 lines — preferred input for LLM analysis
                    # The pipe must not suppress the exit code, so use a PIPESTATUS check.
                    set -o pipefail
                    xcodebuild archive \\
                        -workspace "${env.XC_WORKSPACE}" \\
                        -scheme    "${env.SCHEME}" \\
                        -configuration Release \\
                        -archivePath "\${WORKSPACE}/build/${env.APP_NAME}.xcarchive" \\
                        -derivedDataPath "\${WORKSPACE}/DerivedData" \\
                        CODE_SIGN_STYLE=Manual \\
                        CODE_SIGN_IDENTITY="${certIdentity}" \\
                        DEVELOPMENT_TEAM=DUMTEAM01 \\
                        CODE_SIGNING_REQUIRED=YES \\
                        CODE_SIGNING_ALLOWED=YES \\
                        ${keychainFlag} \\
                        2>&1 | tee "\${WORKSPACE}/build/xcodebuild-raw.log" \\
                               | xcpretty 2>&1 | tee "\${WORKSPACE}/build/xcodebuild-errors.log"

                    echo "Archive complete: build/${env.APP_NAME}.xcarchive"
                    echo "Raw log   : \$(wc -l < "\${WORKSPACE}/build/xcodebuild-raw.log" | tr -d ' ') lines"
                    echo "Error log : \$(wc -l < "\${WORKSPACE}/build/xcodebuild-errors.log" | tr -d ' ') lines"
                    """
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }

        // ── Stage 6: Package IPA ──────────────────────────────────────────────
        // DUMMY_SIGNING=true:
        //   Manually packages the .app into a .ipa (Payload/ zip).
        //   Bypasses xcodebuild -exportArchive which validates the Apple cert chain.
        //   The resulting IPA is real and structurally correct — just not installable.
        //
        // DUMMY_SIGNING=false:
        //   Uses xcodebuild -exportArchive with ExportOptions-enterprise.plist.
        //   Requires a valid Apple-signed provisioning profile.
        stage('Package IPA') {
            agent { label params.AGENT }
            options { skipDefaultCheckout(true) }
            steps {
                script {
                    if (params.DUMMY_SIGNING) {

                        sh """#!/bin/bash
                        set -euo pipefail
                        export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

                        ARCHIVE_PATH="\${WORKSPACE}/build/${env.APP_NAME}.xcarchive"
                        IPA_DIR="\${WORKSPACE}/build/export"
                        VERSION="\$(git describe --tags --always 2>/dev/null || git rev-parse --short HEAD)"
                        IPA_NAME="${env.APP_NAME}-\${VERSION}-dummy-\${BUILD_NUMBER}.ipa"

                        # Locate the built .app inside the archive
                        APP_PATH="\$(find "\${ARCHIVE_PATH}/Products/Applications" \\
                                       -maxdepth 1 -name "*.app" | head -1)"

                        if [[ -z "\${APP_PATH}" ]]; then
                            echo "ERROR: No .app found in archive at \${ARCHIVE_PATH}" >&2
                            exit 1
                        fi
                        echo "Found app: \${APP_PATH}"

                        # Package into IPA (standard Payload/ structure)
                        rm -rf "\${IPA_DIR}"
                        mkdir -p "\${IPA_DIR}/Payload"
                        cp -r "\${APP_PATH}" "\${IPA_DIR}/Payload/"

                        cd "\${IPA_DIR}"
                        zip -r "\${IPA_NAME}" Payload/ -x "*.DS_Store"
                        rm -rf Payload

                        echo "IPA created: \${IPA_DIR}/\${IPA_NAME}"
                        echo "Size: \$(du -sh "\${IPA_DIR}/\${IPA_NAME}" | cut -f1)"

                        # Record IPA path for archiveArtifacts
                        echo "\${IPA_DIR}/\${IPA_NAME}" > "\${WORKSPACE}/build/ipa_path.txt"
                        """

                    } else {

                        sh """#!/bin/bash
                        set -euo pipefail
                        export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

                        ARCHIVE_PATH="\${WORKSPACE}/build/${env.APP_NAME}.xcarchive"
                        IPA_DIR="\${WORKSPACE}/build/export"
                        PULSE_DIR=""
                        for P in "\${HOME:-/Users/dk}/Documents/git/pulse" "/home/dk/Documents/git/pulse"; do
                            [ -f "\$P/jenkins-k8s/ios/option-4-enterprise/dummy-signing/ExportOptions-enterprise.plist" ] && PULSE_DIR="\$P" && break
                        done
                        [ -z "\$PULSE_DIR" ] && echo "ERROR: cannot find ExportOptions-enterprise.plist — clone pulse repo to ~/Documents/git/pulse" && exit 1
                        EXPORT_PLIST="\$PULSE_DIR/jenkins-k8s/ios/option-4-enterprise/dummy-signing/ExportOptions-enterprise.plist"

                        echo "── xcodebuild -exportArchive ────────────────────────"

                        xcodebuild -exportArchive \\
                            -archivePath      "\${ARCHIVE_PATH}" \\
                            -exportPath       "\${IPA_DIR}" \\
                            -exportOptionsPlist "\${EXPORT_PLIST}" \\
                            | xcpretty

                        echo "Export complete. IPA files:"
                        find "\${IPA_DIR}" -name "*.ipa" -exec ls -lh {} \\;
                        find "\${IPA_DIR}" -name "*.ipa" | head -1 > "\${WORKSPACE}/build/ipa_path.txt"
                        """
                    }
                }
            }
            post {
                failure { script { env.FAILED_STAGE = env.STAGE_NAME } }
            }
        }
    }

    // ── Post Actions ──────────────────────────────────────────────────────────
    post {

        always {
            node(params.AGENT) {
                script {
                    // Archive IPA regardless of pass/fail
                    archiveArtifacts(
                        artifacts:        'build/export/*.ipa',
                        allowEmptyArchive: true,
                        fingerprint:       true
                    )

                    // Teardown: remove dummy or per-build keychain
                    sh """#!/bin/bash
                    export PATH="/usr/bin:/bin:\${PATH:-}"

                    if ${params.DUMMY_SIGNING}; then
                        security delete-keychain "${env.DUMMY_KEYCHAIN}" 2>/dev/null \\
                            && echo "Dummy keychain removed: ${env.DUMMY_KEYCHAIN}" \\
                            || echo "Dummy keychain already gone."
                    else
                        REAL_KC="ios-banknow-real-${env.BUILD_NUMBER}.keychain"
                        security delete-keychain "\${REAL_KC}" 2>/dev/null \\
                            && echo "Per-build keychain removed: \${REAL_KC}" \\
                            || echo "Per-build keychain already gone."
                    fi

                    # Restore login keychain as default
                    security list-keychains -d user -s login.keychain 2>/dev/null || true
                    security default-keychain -s login.keychain 2>/dev/null || true
                    """
                }
            }
        }

        success {
            script {
                echo "Build succeeded: ${env.APP_NAME} ${env.ENVIRONMENT} #${env.BUILD_NUMBER}"
                if (params.NOTIFY_EMAIL?.trim()) {
                    def duration  = currentBuild.durationString ?: 'N/A'
                    def mode      = params.DUMMY_SIGNING ? 'Dummy (self-signed)' : 'Enterprise (real)'
                    def pkgMgr    = params.USE_PNPM ? 'pnpm' : 'npm'
                    emailext(
                        subject: "&#9989; iOS BUILD SUCCESS: ${env.APP_NAME} ${env.ENVIRONMENT} #${env.BUILD_NUMBER}",
                        mimeType: 'text/html',
                        to: params.NOTIFY_EMAIL,
                        body: """
<html>
<body style="font-family:Arial,sans-serif;font-size:14px;color:#333;">
  <h2 style="color:#008000;">&#9989; iOS Build Successful</h2>

  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;width:100%;max-width:700px;">
    <tr style="background:#f7f7f7;"><td width="200"><b>App</b></td>           <td>${env.APP_NAME}</td></tr>
    <tr>                            <td><b>Environment</b></td>               <td>${params.ENVIRONMENT}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Build #</b></td>                   <td>${env.BUILD_NUMBER}</td></tr>
    <tr>                            <td><b>Duration</b></td>                  <td>${duration}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Agent</b></td>                     <td>${params.AGENT}</td></tr>
    <tr>                            <td><b>Signing Mode</b></td>              <td>${mode}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Package Manager</b></td>           <td>${pkgMgr}</td></tr>
    <tr>                            <td><b>Tests Run</b></td>                 <td>${params.RUN_TESTS}</td></tr>
    <tr style="background:#f7f7f7;">
      <td><b>IPA Artifact</b></td>
      <td><a href="${env.BUILD_URL}artifact">Download IPA</a></td>
    </tr>
    <tr>
      <td><b>Build URL</b></td>
      <td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
    </tr>
  </table>
  <br/>
  <p>Regards,<br/><b>Jenkins CI</b></p>
</body>
</html>
                        """
                    )
                }
            }
        }

        // ── Failure: LLM analysis + email ────────────────────────────────────
        // LLM analysis flow (all sandbox-safe — no rawBuild):
        //   1. Universal PULSE_DIR resolver finds llm-analysis.sh on any agent type
        //      (Mac Mini: ~/Documents/git/pulse, Linux: /home/dk/Documents/git/pulse)
        //   2. extract-build-errors.sh distils xcodebuild output to ≤200 lines
        //   3. Run jenkins-k8s/shared/llm-analysis.sh (queries Ollama endpoints
        //      at 100.89.50.27:11434 and 100.104.14.62:21434 via Tailscale)
        //   4. readFile('llm-analysis.md') — whitelisted Jenkins step
        //   5. Archive llm-analysis.md as a build artifact
        //   7. Send failure email with LLM report embedded
        //
        // Requires Jenkins credential: jenkins-admin-creds (Username/Password)
        //   Manage Jenkins → Credentials → Global → Add
        //   Kind: Username/Password
        //   ID:   jenkins-admin-creds
        //   User: admin   Password: <jenkins admin password>
        //
        // Requires Ollama reachable from the Mac Mini agent via Tailscale.
        // Both endpoints are queried; best available model is selected automatically.
        failure {
            script {
                echo "Build FAILED at stage: ${env.FAILED_STAGE ?: 'Unknown'}"

                node(params.AGENT) {
                    def llmReport    = ''
                    def llmAvailable = false

                    try {
                        // ── Smart log extraction ──────────────────────────────
                        // Universal PULSE_DIR resolver — works on Mac Mini agents
                        // (~/Documents/git/pulse) and Linux agents (/home/dk/Documents/git/pulse)
                        // without needing checkout scm.
                        sh """#!/bin/bash -l
                        set -euo pipefail
                        export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

                        PULSE_DIR=""
                        for P in "\${HOME:-/Users/dk}/Documents/git/pulse" "/home/dk/Documents/git/pulse"; do
                            [ -f "\$P/jenkins-k8s/shared/llm-analysis.sh" ] && PULSE_DIR="\$P" && break
                        done
                        [ -z "\$PULSE_DIR" ] && echo "LLM scripts not found on this agent — skipping analysis" && exit 1
                        echo "PULSE_DIR=\$PULSE_DIR"

                        export WORKSPACE="\${WORKSPACE}"
                        export MAX_LINES=200
                        export MAX_LINE_LEN=300

                        bash "\$PULSE_DIR/jenkins-k8s/shared/extract-build-errors.sh" > build-error-report.txt 2>/dev/null || true

                        REPORT_LINES=\$(wc -l < build-error-report.txt | tr -d ' ')
                        echo "Error report: \${REPORT_LINES} lines (sent to LLM)"
                        """

                        // 2. Run LLM analysis with the concise error report
                        sh """#!/bin/bash -l
                        set -euo pipefail
                        export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:\${PATH:-}"

                        PULSE_DIR=""
                        for P in "\${HOME:-/Users/dk}/Documents/git/pulse" "/home/dk/Documents/git/pulse"; do
                            [ -f "\$P/jenkins-k8s/shared/llm-analysis.sh" ] && PULSE_DIR="\$P" && break
                        done
                        [ -z "\$PULSE_DIR" ] && echo "PULSE_DIR not found — skipping LLM analysis" && exit 1

                        export FAILED_STAGE="${env.FAILED_STAGE ?: 'Unknown'}"
                        export BUILD_NUMBER="${env.BUILD_NUMBER}"
                        export JOB_NAME="${env.JOB_NAME}"
                        export BUILD_TYPE="iOS-Enterprise"
                        export APP_NAME="${env.APP_NAME}"
                        export ENVIRONMENT="${params.ENVIRONMENT}"

                        # ERROR_SNIPPET: the smart-extracted error report (≤200 lines)
                        # LOG_TAIL: kept for compatibility but set to same content
                        export ERROR_SNIPPET="\$(cat build-error-report.txt 2>/dev/null || echo 'Extraction failed')"
                        export LOG_TAIL="\${ERROR_SNIPPET}"

                        # Configurable endpoints — overridden by pipeline parameters
                        export LLM_ENDPOINT_A="${params.LLM_ENDPOINT_A}"
                        export LLM_ENDPOINT_B="${params.LLM_ENDPOINT_B}"

                        export EXTRA_CONTEXT="Signing mode: ${params.DUMMY_SIGNING ? 'dummy self-signed (no Apple account)' : 'real enterprise certificate'}.
macOS Tahoe (26) — known codesign issues:
  - errSecInternalComponent: missing security set-key-partition-list on keychain
  - errSecInteractionNotAllowed: keychain locked under launchd Jenkins agent
  - Provisioning profile requires Apple CMS signing for device installation
React Native app using ${params.USE_PNPM ? 'pnpm' : 'npm'} for node packages.
CocoaPods for iOS dependency management.
Archive stage pipes through tee + xcpretty; raw log saved to build/xcodebuild-raw.log.
DUMMY_SIGNING=${params.DUMMY_SIGNING} — IPA packaged via manual Payload/ zip, not -exportArchive."

                        bash "\$PULSE_DIR/jenkins-k8s/shared/llm-analysis.sh"
                        echo "LLM analysis complete → llm-analysis.md"
                        """

                        // 3. Archive the report as a build artifact
                        archiveArtifacts artifacts: 'llm-analysis.md', allowEmptyArchive: true

                        // 4. Read the report — readFile() is sandbox-safe (whitelisted Jenkins step)
                        //    This is the correct alternative to currentBuild.rawBuild.getLog()
                        if (fileExists('llm-analysis.md')) {
                            llmReport    = readFile('llm-analysis.md')
                            llmAvailable = true
                            echo "LLM analysis loaded: ${llmReport.size()} characters"
                        }

                    } catch (llmErr) {
                        echo "LLM analysis skipped: ${llmErr.message}"
                        llmReport = "LLM analysis could not run: ${llmErr.message}\n\n" +
                                    "Check:\n" +
                                    "• jenkins-admin-creds credential exists in Jenkins\n" +
                                    "• Ollama endpoints reachable via Tailscale (100.89.50.27:11434, 100.104.14.62:21434)\n" +
                                    "• jq is installed on the Mac Mini agent (brew install jq)"
                    }

                    // 5. Send failure email with LLM report embedded
                    if (params.NOTIFY_EMAIL?.trim()) {
                        def duration   = currentBuild.durationString ?: 'N/A'
                        def mode       = params.DUMMY_SIGNING ? 'Dummy (self-signed)' : 'Enterprise (real)'
                        def llmSection = llmAvailable
                            ? """
  <h3 style="border-bottom:2px solid #cc0000;padding-bottom:6px;margin-top:24px;">
    &#129302; LLM Failure Analysis
    <span style="font-size:11px;font-weight:normal;color:#666;">
      (via Ollama — Tailscale endpoints)
    </span>
  </h3>
  <div style="background:#1e1e1e;color:#d4d4d4;padding:14px;border-radius:6px;
              font-family:monospace;font-size:12px;white-space:pre-wrap;
              word-break:break-all;max-height:600px;overflow-y:auto;">
${llmReport.take(8000)}${llmReport.size() > 8000 ? '\n\n... (truncated — see llm-analysis.md artifact for full report)' : ''}
  </div>
  <p style="font-size:12px;color:#666;margin-top:8px;">
    Full report: <a href="${env.BUILD_URL}artifact/llm-analysis.md">Download llm-analysis.md</a>
  </p>"""
                            : """
  <h3 style="border-bottom:1px solid #ccc;padding-bottom:6px;margin-top:24px;">
    &#129302; LLM Failure Analysis
  </h3>
  <p style="color:#666;">${llmReport}</p>"""

                        emailext(
                            subject: "&#10060; iOS BUILD FAILED: ${env.APP_NAME} ${params.ENVIRONMENT} #${env.BUILD_NUMBER}",
                            mimeType: 'text/html',
                            to: params.NOTIFY_EMAIL,
                            attachmentsPattern: 'llm-analysis.md',
                            body: """
<html>
<body style="font-family:Arial,sans-serif;font-size:14px;color:#333;">
  <h2 style="color:#cc0000;">&#10060; iOS Build Failed</h2>

  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;width:100%;max-width:720px;">
    <tr style="background:#f7f7f7;"><td width="200"><b>App</b></td>          <td>${env.APP_NAME}</td></tr>
    <tr>                            <td><b>Environment</b></td>              <td>${params.ENVIRONMENT}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Build #</b></td>                  <td>${env.BUILD_NUMBER}</td></tr>
    <tr>                            <td><b>Duration</b></td>                 <td>${duration}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Agent</b></td>                    <td>${params.AGENT}</td></tr>
    <tr>                            <td><b>Signing Mode</b></td>             <td>${mode}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Package Manager</b></td>          <td>${params.USE_PNPM ? 'pnpm' : 'npm'}</td></tr>
    <tr style="background:#fee;">
      <td><b>Failed Stage</b></td>
      <td><b style="color:#cc0000;">${env.FAILED_STAGE ?: 'Unknown'}</b></td>
    </tr>
    <tr>
      <td><b>Console Log</b></td>
      <td><a href="${env.BUILD_URL}console">View full console</a></td>
    </tr>
    <tr style="background:#f7f7f7;">
      <td><b>Artifacts</b></td>
      <td><a href="${env.BUILD_URL}artifact">Browse artifacts (llm-analysis.md)</a></td>
    </tr>
    <tr>
      <td><b>Build URL</b></td>
      <td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
    </tr>
  </table>

  ${llmSection}

  <br/>
  <p>Regards,<br/><b>Jenkins CI</b></p>
</body>
</html>
                            """
                        )
                    }
                }
            }
        }
    }
}
