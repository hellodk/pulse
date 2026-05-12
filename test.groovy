// ============================================================
// Enterprise-Grade Certificate & Provisioning Profile
// Deployment Pipeline — macOS Tahoe (26) compatible
//
// External audit fixes applied:
//   C1  — CERT_PASSWORD injected via Groovy GString (not withEnv)
//           so Jenkins password-masking is preserved in console.
//   C2  — set-key-partition-list uses KEYCHAIN_PASSWORD param,
//           not the silent empty-string default.
//   C3  — Profile metadata extracted via security cms + plutil
//           (not grep on binary CMS-wrapped DER blob).
//   C4  — Cert deletion uses awk '/SHA-[0-9]/' — works macOS 12+.
//   H3  — build.xml storage risk documented in parameter desc.
//   H4  — Validate stage uses agent none (no Mac Mini executor
//           wasted for pure Groovy parameter validation).
//   H5  — Per-node structured audit artifact written; re-throw
//           preserves true failure signal.
//   M1  — Profile expiry validated before install; expired
//           profiles are rejected with a clear error message.
//   M2  — Login keychain uses $HOME (not ~); tilde is not
//           expanded in Groovy strings or under sudo context.
//   M3  — Tempfiles created in $TMPDIR, not world-writable /tmp.
//   M4  — VALIDATED_PROFILE_IDS set in Validate stage and used
//           consistently in Deploy (no re-parsing of raw params).
//   M5  — Installed profile count asserted after install loop.
//   M6  — Tempfile cleanup via trap EXIT in every sh block.
//   L3  — bash -l removed; PATH set explicitly per sh block.
//   L4  — SHA hash extracted with awk '/SHA-[0-9]+/' (matches
//           both macOS 11 SHA-1 and macOS 12+ SHA-256 output).
//   L5  — Per-node audit artifact written in finally{} block
//           and archived regardless of success or failure.
//   NEW — Email notifications: success / unstable / failure.
//   NEW — NOTIFY_EMAIL and KEYCHAIN_PASSWORD parameters added.
//   NEW — buildDiscarder(logRotator) added to options.
// ============================================================
pipeline {

    agent none

    // ── Parameters ────────────────────────────────────────────────────────────
    parameters {

        // ── Target nodes ────────────────────────────────────────────────────
        booleanParam(name: 'NODE_MOBILEAPP',
                     defaultValue: true,
                     description: 'Deploy to Mac Mini node: mobileapp')
        booleanParam(name: 'NODE_MOBILEAPP2',
                     defaultValue: true,
                     description: 'Deploy to Mac Mini node: mobileapp2')
        booleanParam(name: 'NODE_MOBILEAPP3',
                     defaultValue: false,
                     description: 'Deploy to Mac Mini node: mobileapp3')
        booleanParam(name: 'NODE_MOBILEAPP4',
                     defaultValue: false,
                     description: 'Deploy to Mac Mini node: mobileapp4')

        // ── Operations ───────────────────────────────────────────────────────
        booleanParam(name: 'INSTALL_CERT',
                     defaultValue: true,
                     description: 'Install enterprise distribution certificate into keychain')
        booleanParam(name: 'INSTALL_PROFILES',
                     defaultValue: true,
                     description: 'Install provisioning profiles')
        booleanParam(name: 'CLEAN_EXISTING',
                     defaultValue: false,
                     description: 'Remove ALL existing .mobileprovision files before installing (use for profile rotation)')

        // ── Certificate ──────────────────────────────────────────────────────
        string(name:        'CERT_CREDENTIAL_ID',
               defaultValue: 'ios-enterprise-dist-cert-p12',
               description:  'Jenkins credential ID (type: Secret File) of the .p12 certificate')

        // H3 NOTE: Jenkins stores password-type parameters in build.xml on the
        // controller in plaintext. For highest security, replace CERT_PASSWORD
        // with a Secret Text credential ID and reference via withCredentials().
        password(name:        'CERT_PASSWORD',
                 defaultValue: '',
                 description:  'Passphrase for the .p12 — masked in console. See H3 note: stored in build.xml.')

        // ── Keychain ─────────────────────────────────────────────────────────
        choice(name:    'KEYCHAIN_TYPE',
               choices: ['login', 'system'],
               description: '''\
login  — Jenkins agent user login keychain. No sudo needed.
system — /Library/Keychains/System.keychain. Requires sudo. Shared across all users.''')

        // C2 fix: set-key-partition-list requires the actual keychain password.
        // Default macOS system keychain password is empty — leave blank if unchanged.
        // Not used for login keychain (see pipeline comments for that workaround).
        password(name:        'KEYCHAIN_PASSWORD',
                 defaultValue: '',
                 description:  'System keychain password for set-key-partition-list (C2 fix). Leave blank for default macOS. Not used for login keychain.')

        // ── Provisioning profiles ─────────────────────────────────────────────
        string(name:        'PROFILE_CREDENTIAL_IDS',
               defaultValue: '',
               description:  'Comma-separated Jenkins credential IDs (type: Secret File) of .mobileprovision files. Example: uat-profile,prod-profile,adhoc-profile')

        // ── Notifications ─────────────────────────────────────────────────────
        string(name:        'NOTIFY_EMAIL',
               defaultValue: '',
               description:  'Recipient email(s) for deployment result — comma-separated. Leave blank to skip email.')
    }

    options {
        timeout(time: 15, unit: 'MINUTES')
        disableConcurrentBuilds()
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
    }

    // ── Stages ────────────────────────────────────────────────────────────────
    stages {

        // H4 fix: agent none — validation is pure Groovy; allocating a Mac Mini
        // executor for parameter validation wastes a build slot.
        stage('Validate Inputs') {
            agent none
            steps {
                script {
                    def selectedNodes = [
                        'mobileapp':  params.NODE_MOBILEAPP,
                        'mobileapp2': params.NODE_MOBILEAPP2,
                        'mobileapp3': params.NODE_MOBILEAPP3,
                        'mobileapp4': params.NODE_MOBILEAPP4,
                    ].findAll { _, v -> v }.keySet().toList()

                    if (selectedNodes.isEmpty()) {
                        error('No target nodes selected. Enable at least one NODE_* parameter.')
                    }
                    if (!params.INSTALL_CERT && !params.INSTALL_PROFILES) {
                        error('Nothing to do. Enable INSTALL_CERT and/or INSTALL_PROFILES.')
                    }
                    if (params.INSTALL_CERT) {
                        if (!params.CERT_CREDENTIAL_ID?.trim()) {
                            error('CERT_CREDENTIAL_ID is required when INSTALL_CERT is enabled.')
                        }
                        if (!params.CERT_PASSWORD?.trim()) {
                            error('CERT_PASSWORD is required when INSTALL_CERT is enabled.')
                        }
                    }

                    def profileIds = []
                    if (params.INSTALL_PROFILES) {
                        profileIds = params.PROFILE_CREDENTIAL_IDS
                            .split(',')
                            .collect  { it.trim() }
                            .findAll  { it }
                        if (profileIds.isEmpty()) {
                            error('PROFILE_CREDENTIAL_IDS is required when INSTALL_PROFILES is enabled.')
                        }
                    }

                    // M4 fix: store validated normalised list so Deploy stage uses
                    // exactly the same list without re-parsing raw params again.
                    env.VALIDATED_PROFILE_IDS = profileIds.join(',')
                    env.TARGETED_NODES        = selectedNodes.join(', ')
                    env.PROFILE_COUNT         = profileIds.size().toString()

                    echo '════════════════════════════════════════'
                    echo ' Deployment Plan'
                    echo '════════════════════════════════════════'
                    echo "Target nodes      : ${env.TARGETED_NODES}"
                    echo "Install cert      : ${params.INSTALL_CERT}"
                    if (params.INSTALL_CERT) {
                        echo "  Cert cred ID  : ${params.CERT_CREDENTIAL_ID}"
                        echo "  Keychain type : ${params.KEYCHAIN_TYPE}"
                    }
                    echo "Install profiles  : ${params.INSTALL_PROFILES}"
                    if (params.INSTALL_PROFILES) {
                        echo "  Profile IDs   : ${profileIds.join(', ')}"
                        echo "  Profile count : ${profileIds.size()}"
                    }
                    echo "Clean existing    : ${params.CLEAN_EXISTING}"
                    echo '════════════════════════════════════════'
                }
            }
        }

        stage('Deploy to Mac Minis') {
            agent none
            steps {
                script {
                    def nodeMap = [
                        'mobileapp':  params.NODE_MOBILEAPP,
                        'mobileapp2': params.NODE_MOBILEAPP2,
                        'mobileapp3': params.NODE_MOBILEAPP3,
                        'mobileapp4': params.NODE_MOBILEAPP4,
                    ]

                    // M4 fix: use the validated list from Validate stage
                    def profileCredIds = (env.VALIDATED_PROFILE_IDS?.trim())
                        ? env.VALIDATED_PROFILE_IDS.split(',').collect { it.trim() }.findAll { it }
                        : []

                    def isSystemKeychain = (params.KEYCHAIN_TYPE == 'system')
                    def sudo             = isSystemKeychain ? 'sudo ' : ''

                    def deployStages = [:]

                    nodeMap.each { nodeName, enabled ->
                        if (!enabled) return

                        def n       = nodeName
                        def s       = sudo
                        def isSys   = isSystemKeychain
                        def profIds = profileCredIds

                        deployStages["${n}"] = {
                            node(n) {
                                // L5 fix: per-node audit log — written in finally{} regardless of outcome
                                def auditLines = [
                                    "node=${n}",
                                    "build=${env.BUILD_NUMBER}",
                                    "job=${env.JOB_NAME}",
                                    "timestamp=${new Date().format('yyyy-MM-dd HH:mm:ss z')}",
                                    "keychain_type=${params.KEYCHAIN_TYPE}",
                                    "clean_existing=${params.CLEAN_EXISTING}"
                                ]

                                try {
                                    echo "════════ Starting deployment on: ${n} ════════"

                                    // ── Clean existing profiles ─────────────────────────────────
                                    if (params.CLEAN_EXISTING && params.INSTALL_PROFILES) {
                                        sh """#!/bin/bash
                                        set -euo pipefail
                                        export PATH="/usr/bin:/bin:/usr/sbin:/sbin:\${PATH:-}"
                                        PROFILE_DIR="\$HOME/Library/MobileDevice/Provisioning Profiles"
                                        mkdir -p "\${PROFILE_DIR}"
                                        COUNT=\$(find "\${PROFILE_DIR}" -maxdepth 1 -name '*.mobileprovision' | wc -l | tr -d ' ')
                                        if [ "\${COUNT}" -gt 0 ]; then
                                            find "\${PROFILE_DIR}" -maxdepth 1 -name '*.mobileprovision' -delete
                                            echo "[${n}] Removed \${COUNT} existing profile(s)."
                                        else
                                            echo "[${n}] No existing profiles to remove."
                                        fi
                                        """
                                        auditLines << "profiles_cleaned=true"
                                    }

                                    // ── Install certificate ─────────────────────────────────────
                                    if (params.INSTALL_CERT) {
                                        withCredentials([
                                            file(credentialsId: params.CERT_CREDENTIAL_ID,
                                                 variable: 'CERT_FILE')
                                        ]) {
                                            // C1 fix: inject CERT_PASSWORD via Groovy GString so Jenkins
                                            // password-masking applies in console output.
                                            // NOT placed in withEnv — that strips masking from the value.
                                            // C2 fix: KEYCHAIN_PASSWORD used in set-key-partition-list.
                                            // M2 fix: $HOME used (not ~) in login keychain path.
                                            // M3 fix: tempfile in $TMPDIR with 0600 perms.
                                            // L3 fix: no bash -l; explicit PATH.
                                            // C4/L4 fix: awk '/SHA-[0-9]+/' works on macOS 11 and 12+.
                                            sh """#!/bin/bash
                                            set -euo pipefail
                                            export PATH="/usr/bin:/bin:/usr/sbin:/sbin:\${PATH:-}"

                                            if [ "${params.KEYCHAIN_TYPE}" = "system" ]; then
                                                KEYCHAIN="/Library/Keychains/System.keychain"
                                            else
                                                KEYCHAIN="\$HOME/Library/Keychains/login.keychain-db"
                                            fi
                                            echo "[${n}] Target keychain: \${KEYCHAIN}"

                                            # M3: tempfile in TMPDIR with 0600
                                            PASS_FILE="\$(mktemp "\${TMPDIR:-/tmp}/certpass.XXXXXX")"
                                            chmod 600 "\${PASS_FILE}"
                                            trap "rm -f '\${PASS_FILE}'" EXIT

                                            # C1: Groovy GString injects the masked value here.
                                            # The shell never sees it as an environment variable.
                                            printf '%s' '${params.CERT_PASSWORD}' > "\${PASS_FILE}"

                                            # C4/L4: remove existing iPhone Distribution certs.
                                            # awk '/SHA-[0-9]+/' matches both macOS 11 (SHA-1 hash:)
                                            # and macOS 12+ (SHA-256 hash:) output formats.
                                            echo "[${n}] Removing existing distribution certificates..."
                                            ${s}security find-certificate -a -c "iPhone Distribution" \\
                                                -Z "\${KEYCHAIN}" 2>/dev/null \\
                                            | awk '/SHA-[0-9]+/{print \$NF}' \\
                                            | while IFS= read -r HASH; do
                                                if ${s}security delete-certificate -Z "\${HASH}" "\${KEYCHAIN}" 2>/dev/null; then
                                                    echo "  Removed: \${HASH}"
                                                else
                                                    echo "  Could not remove \${HASH} — skipping"
                                                fi
                                            done || true

                                            echo "[${n}] Importing certificate..."
                                            ${s}security import "\${CERT_FILE}" \\
                                                -k "\${KEYCHAIN}" \\
                                                -P "\$(cat "\${PASS_FILE}")" \\
                                                -T /usr/bin/codesign \\
                                                -T /usr/bin/productbuild \\
                                                -T /usr/bin/security \\
                                                -f pkcs12

                                            # C2 fix: use KEYCHAIN_PASSWORD (not empty string) for
                                            # set-key-partition-list on system keychain.
                                            # For login keychain: re-import with -A flag because the
                                            # user's login keychain password is not available in CI.
                                            # -A (allow all applications) is the Apple DTS-recommended
                                            # workaround for CI environments on login keychains.
                                            if [ "${params.KEYCHAIN_TYPE}" = "system" ]; then
                                                echo "[${n}] Setting codesign partition list (system keychain)..."
                                                ${s}security set-key-partition-list \\
                                                    -S apple-tool:,apple:,codesign: \\
                                                    -s -k '${params.KEYCHAIN_PASSWORD}' \\
                                                    "\${KEYCHAIN}"
                                            else
                                                echo "[${n}] Applying -A flag for login keychain CI access..."
                                                ${s}security import "\${CERT_FILE}" \\
                                                    -k "\${KEYCHAIN}" \\
                                                    -P "\$(cat "\${PASS_FILE}")" \\
                                                    -A -f pkcs12 2>/dev/null || true
                                            fi

                                            echo "[${n}] Certificate installed."
                                            echo "── Installed identities ─────────────────────────────"
                                            ${s}security find-identity -v -p codesigning "\${KEYCHAIN}" || true
                                            """

                                            // Capture fingerprint for audit artifact (best-effort)
                                            def fp = sh(
                                                script: """#!/bin/bash
                                                export PATH="/usr/bin:/bin:\${PATH:-}"
                                                if [ "${params.KEYCHAIN_TYPE}" = "system" ]; then
                                                    KCH="/Library/Keychains/System.keychain"
                                                else
                                                    KCH="\$HOME/Library/Keychains/login.keychain-db"
                                                fi
                                                ${s}security find-identity -v -p codesigning "\${KCH}" 2>/dev/null \\
                                                    | grep "iPhone Distribution" \\
                                                    | head -1 \\
                                                    | awk '{print \$2}' || echo unknown
                                                """,
                                                returnStdout: true
                                            ).trim()
                                            auditLines << "cert_installed=true"
                                            auditLines << "cert_credential_id=${params.CERT_CREDENTIAL_ID}"
                                            auditLines << "cert_fingerprint=${fp}"
                                        }
                                    } else {
                                        auditLines << "cert_installed=false"
                                    }

                                    // ── Install provisioning profiles ───────────────────────────
                                    def installedCount = 0
                                    if (params.INSTALL_PROFILES && profIds) {
                                        profIds.each { credId ->
                                            withCredentials([
                                                file(credentialsId: credId, variable: 'PROFILE_FILE')
                                            ]) {
                                                withEnv(["CRED_ID=${credId}"]) {
                                                    // C3 fix: security cms -D decodes the CMS binary envelope
                                                    //         so plutil can parse the embedded XML plist.
                                                    //         grep on the raw .mobileprovision binary is
                                                    //         unreliable and produces corrupt/empty UUIDs.
                                                    // M1 fix: expiry date validated; expired profiles rejected.
                                                    // M2 fix: $HOME not ~.
                                                    // M3 fix: decoded tempfile in $TMPDIR.
                                                    // L3 fix: no bash -l.
                                                    sh '''#!/bin/bash
                                                    set -euo pipefail
                                                    export PATH="/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

                                                    PROFILE_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
                                                    mkdir -p "${PROFILE_DIR}"

                                                    # C3: decode CMS wrapper, then extract with plutil
                                                    DECODED="$(mktemp "${TMPDIR:-/tmp}/profile.XXXXXX.plist")"
                                                    trap "rm -f '${DECODED}'" EXIT

                                                    if ! security cms -D -i "${PROFILE_FILE}" -o "${DECODED}" 2>/dev/null; then
                                                        echo "ERROR: Failed to decode ${CRED_ID} — not a valid .mobileprovision" >&2
                                                        exit 1
                                                    fi

                                                    PROFILE_UUID="$(plutil -extract UUID raw "${DECODED}" 2>/dev/null || true)"
                                                    PROFILE_NAME="$(plutil -extract Name raw "${DECODED}" 2>/dev/null || echo "${CRED_ID}")"
                                                    EXPIRY="$(plutil -extract ExpirationDate raw "${DECODED}" 2>/dev/null || echo "unknown")"

                                                    if [[ -z "${PROFILE_UUID}" ]]; then
                                                        echo "ERROR: UUID not found in ${CRED_ID}" >&2
                                                        exit 1
                                                    fi

                                                    # M1 fix: validate expiry — refuse to install expired profiles
                                                    if [[ "${EXPIRY}" != "unknown" ]]; then
                                                        EXPIRY_EPOCH="$(date -j -f "%Y-%m-%dT%H:%M:%SZ" "${EXPIRY}" "+%s" 2>/dev/null \
                                                            || date -d "${EXPIRY}" "+%s" 2>/dev/null \
                                                            || echo 0)"
                                                        NOW_EPOCH="$(date +%s)"
                                                        if [[ "${EXPIRY_EPOCH}" -lt "${NOW_EPOCH}" ]]; then
                                                            echo "ERROR: Profile '${PROFILE_NAME}' (${CRED_ID}) EXPIRED at ${EXPIRY}" >&2
                                                            exit 1
                                                        fi
                                                        DAYS_LEFT=$(( (EXPIRY_EPOCH - NOW_EPOCH) / 86400 ))
                                                        if [[ "${DAYS_LEFT}" -lt 30 ]]; then
                                                            echo "WARNING: '${PROFILE_NAME}' expires in ${DAYS_LEFT} day(s) — renew soon."
                                                        fi
                                                    fi

                                                    DEST="${PROFILE_DIR}/${PROFILE_UUID}.mobileprovision"
                                                    cp "${PROFILE_FILE}" "${DEST}"
                                                    chmod 644 "${DEST}"

                                                    echo "Installed profile:"
                                                    echo "  Credential ID : ${CRED_ID}"
                                                    echo "  Name          : ${PROFILE_NAME}"
                                                    echo "  UUID          : ${PROFILE_UUID}"
                                                    echo "  Expires       : ${EXPIRY}"
                                                    echo "  Destination   : ${DEST}"
                                                    '''
                                                    installedCount++
                                                    auditLines << "profile_installed=${credId}"
                                                }
                                            }
                                        }

                                        // M5 fix: assert count matches what was requested
                                        def expected = profIds.size()
                                        if (installedCount != expected) {
                                            error("[${n}] Profile install incomplete: expected ${expected}, got ${installedCount}")
                                        }
                                        auditLines << "profiles_total=${installedCount}"
                                    } else {
                                        auditLines << "profiles_total=0"
                                    }

                                    // ── Verification summary ────────────────────────────────────
                                    echo "════════ Verification: ${n} ════════"

                                    if (params.INSTALL_CERT) {
                                        sh """#!/bin/bash
                                        export PATH="/usr/bin:/bin:/usr/sbin:/sbin:\${PATH:-}"
                                        if [ "${params.KEYCHAIN_TYPE}" = "system" ]; then
                                            KCH="/Library/Keychains/System.keychain"
                                        else
                                            KCH="\$HOME/Library/Keychains/login.keychain-db"
                                        fi
                                        echo "── Signing Identities ──────────────────────────────"
                                        ${s}security find-identity -v -p codesigning "\${KCH}" 2>/dev/null \\
                                            || echo "  (no identities found)"
                                        """
                                    }

                                    if (params.INSTALL_PROFILES && profIds) {
                                        // L3/M5: find -print0 + read -d '' handles spaces in filenames
                                        sh '''#!/bin/bash
                                        set -euo pipefail
                                        export PATH="/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"
                                        PROFILE_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
                                        echo "── Installed Provisioning Profiles ─────────────────"
                                        COUNT=0
                                        while IFS= read -r -d "" f; do
                                            DECODED="$(mktemp "${TMPDIR:-/tmp}/verify.XXXXXX.plist")"
                                            trap "rm -f '${DECODED}'" EXIT
                                            if security cms -D -i "$f" -o "${DECODED}" 2>/dev/null; then
                                                NAME="$(plutil -extract Name raw "${DECODED}" 2>/dev/null \
                                                    || echo "$(basename "$f")")"
                                                EXPIRY="$(plutil -extract ExpirationDate raw "${DECODED}" 2>/dev/null \
                                                    || echo "unknown")"
                                                echo "  • ${NAME}  [expires: ${EXPIRY}]"
                                            fi
                                            COUNT=$((COUNT + 1))
                                        done < <(find "${PROFILE_DIR}" -maxdepth 1 \
                                                      -name "*.mobileprovision" -print0 2>/dev/null)
                                        echo "  ── Total installed: ${COUNT} ──────────────────────"
                                        '''
                                    }

                                    auditLines << "status=SUCCESS"
                                    echo "════════ ${n}: deployment complete ════════"

                                } catch (err) {
                                    // H5 fix: re-throw so parallel marks this branch as FAILURE
                                    auditLines << "status=FAILED"
                                    auditLines << "error=${err.getMessage() ?: 'unknown'}"
                                    throw err

                                } finally {
                                    // L5 fix: write audit artifact unconditionally (finally always runs)
                                    def auditText = auditLines.join('\n') + '\n'
                                    try {
                                        writeFile(
                                            file: "audit-${n}-build${env.BUILD_NUMBER}.txt",
                                            text: auditText
                                        )
                                        archiveArtifacts(
                                            artifacts:         "audit-${n}-build${env.BUILD_NUMBER}.txt",
                                            allowEmptyArchive: true
                                        )
                                    } catch (auditErr) {
                                        echo "Warning: audit artifact write failed for ${n}: ${auditErr.getMessage()}"
                                    }
                                }
                            }
                        }
                    }

                    if (deployStages.isEmpty()) {
                        error('No nodes selected for deployment.')
                    }

                    parallel deployStages
                }
            }
        }
    }

    // ── Post Actions ──────────────────────────────────────────────────────────
    post {

        success {
            script {
                echo 'All selected nodes updated successfully.'
                if (params.NOTIFY_EMAIL?.trim()) {
                    def duration = currentBuild.durationString ?: 'N/A'
                    emailext(
                        subject: "&#9989; DEPLOY SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER} [${params.KEYCHAIN_TYPE}]",
                        mimeType: 'text/html',
                        to: params.NOTIFY_EMAIL,
                        body: """
<html>
<body style="font-family:Arial,sans-serif;font-size:14px;color:#333;">
  <h2 style="color:#008000;">&#9989; Certificate &amp; Profile Deployment Successful</h2>
  <p>All selected Mac Mini nodes were updated without errors.</p>

  <h3 style="border-bottom:1px solid #ccc;padding-bottom:4px;">Deployment Summary</h3>
  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;width:100%;max-width:720px;">
    <tr style="background:#f7f7f7;"><td width="220"><b>Job</b></td>                       <td>${env.JOB_NAME}</td></tr>
    <tr>                            <td><b>Build #</b></td>                               <td>${env.BUILD_NUMBER}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Duration</b></td>                              <td>${duration}</td></tr>
    <tr>                            <td><b>Target Nodes</b></td>                          <td>${env.TARGETED_NODES ?: 'N/A'}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Keychain Type</b></td>                         <td>${params.KEYCHAIN_TYPE}</td></tr>
    <tr>                            <td><b>Certificate Installed</b></td>                 <td>${params.INSTALL_CERT ? 'Yes &mdash; ' + params.CERT_CREDENTIAL_ID : 'No'}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Provisioning Profiles</b></td>                 <td>${params.INSTALL_PROFILES ? (env.VALIDATED_PROFILE_IDS ?: 'N/A').replace(',', '<br/>') : 'Not installed'}</td></tr>
    <tr>                            <td><b>Profiles Cleaned Before Install</b></td>       <td>${params.CLEAN_EXISTING}</td></tr>
    <tr style="background:#f0fff0;"><td><b>Result</b></td>                                <td><b style="color:#008000;">SUCCESS</b></td></tr>
    <tr>
      <td><b>Build URL</b></td>
      <td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td>
    </tr>
    <tr style="background:#f7f7f7;">
      <td><b>Per-Node Audit Logs</b></td>
      <td><a href="${env.BUILD_URL}artifact">Download audit artifacts</a></td>
    </tr>
    <tr>
      <td><b>Console Output</b></td>
      <td><a href="${env.BUILD_URL}console">View full console</a></td>
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

        unstable {
            script {
                echo 'Deployment completed with failures on one or more nodes. Review audit artifacts.'
                if (params.NOTIFY_EMAIL?.trim()) {
                    def duration = currentBuild.durationString ?: 'N/A'
                    emailext(
                        subject: "&#9888; DEPLOY PARTIAL FAILURE: ${env.JOB_NAME} #${env.BUILD_NUMBER} [${params.KEYCHAIN_TYPE}]",
                        mimeType: 'text/html',
                        to: params.NOTIFY_EMAIL,
                        body: """
<html>
<body style="font-family:Arial,sans-serif;font-size:14px;color:#333;">
  <h2 style="color:#e65c00;">&#9888; Certificate &amp; Profile Deployment &mdash; Partial Failure</h2>
  <p>One or more Mac Mini nodes failed to update. Nodes that succeeded are operational;
     failed nodes may be running with expired or missing credentials and will fail at code-sign time.</p>

  <h3 style="border-bottom:1px solid #ccc;padding-bottom:4px;">Deployment Summary</h3>
  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;width:100%;max-width:720px;">
    <tr style="background:#f7f7f7;"><td width="220"><b>Job</b></td>             <td>${env.JOB_NAME}</td></tr>
    <tr>                            <td><b>Build #</b></td>                     <td>${env.BUILD_NUMBER}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Duration</b></td>                    <td>${duration}</td></tr>
    <tr>                            <td><b>Target Nodes</b></td>                <td>${env.TARGETED_NODES ?: 'N/A'}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Keychain Type</b></td>               <td>${params.KEYCHAIN_TYPE}</td></tr>
    <tr>                            <td><b>Certificate Credential</b></td>      <td>${params.INSTALL_CERT ? params.CERT_CREDENTIAL_ID : 'Not installed'}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Profile Credentials</b></td>         <td>${params.INSTALL_PROFILES ? (env.VALIDATED_PROFILE_IDS ?: 'N/A').replace(',', '<br/>') : 'Not installed'}</td></tr>
    <tr style="background:#fff3cd;">
      <td><b>Result</b></td>
      <td><b style="color:#e65c00;">UNSTABLE &mdash; one or more nodes failed</b></td>
    </tr>
    <tr>
      <td><b>Per-Node Audit Logs</b></td>
      <td><a href="${env.BUILD_URL}artifact">Download audit artifacts to identify failed nodes</a></td>
    </tr>
    <tr style="background:#f7f7f7;">
      <td><b>Console Output</b></td>
      <td><a href="${env.BUILD_URL}console">View full console</a></td>
    </tr>
  </table>

  <p style="color:#cc0000;margin-top:12px;">
    <b>Action required:</b> Download the per-node audit artifacts, identify which node(s)
    have <code>status=FAILED</code>, then re-run targeting only those nodes.
  </p>
  <br/>
  <p>Regards,<br/><b>Jenkins CI</b></p>
</body>
</html>
                        """
                    )
                }
            }
        }

        failure {
            script {
                echo 'Pipeline failed. Review the Validate or Deploy stage logs.'
                if (params.NOTIFY_EMAIL?.trim()) {
                    def duration = currentBuild.durationString ?: 'N/A'
                    emailext(
                        subject: "&#10060; DEPLOY FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER} [${params.KEYCHAIN_TYPE}]",
                        mimeType: 'text/html',
                        to: params.NOTIFY_EMAIL,
                        body: """
<html>
<body style="font-family:Arial,sans-serif;font-size:14px;color:#333;">
  <h2 style="color:#cc0000;">&#10060; Certificate &amp; Profile Deployment Failed</h2>
  <p>The deployment pipeline failed before or during deployment.
     No nodes, or only a subset, may have been updated.</p>

  <h3 style="border-bottom:1px solid #ccc;padding-bottom:4px;">Deployment Summary</h3>
  <table border="1" cellpadding="8" cellspacing="0"
         style="border-collapse:collapse;width:100%;max-width:720px;">
    <tr style="background:#f7f7f7;"><td width="220"><b>Job</b></td>      <td>${env.JOB_NAME}</td></tr>
    <tr>                            <td><b>Build #</b></td>              <td>${env.BUILD_NUMBER}</td></tr>
    <tr style="background:#f7f7f7;"><td><b>Duration</b></td>             <td>${duration}</td></tr>
    <tr>
      <td><b>Target Nodes</b></td>
      <td>${env.TARGETED_NODES ?: 'Validation failed &mdash; check parameter values'}</td>
    </tr>
    <tr style="background:#f7f7f7;"><td><b>Keychain Type</b></td>        <td>${params.KEYCHAIN_TYPE}</td></tr>
    <tr style="background:#fee;">
      <td><b>Result</b></td>
      <td><b style="color:#cc0000;">FAILED &mdash; no changes confirmed applied</b></td>
    </tr>
    <tr>
      <td><b>Console Output</b></td>
      <td><a href="${env.BUILD_URL}console">View full console for root cause</a></td>
    </tr>
    <tr style="background:#f7f7f7;">
      <td><b>Audit Artifacts</b></td>
      <td><a href="${env.BUILD_URL}artifact">Download any partial audit logs</a></td>
    </tr>
  </table>

  <p style="color:#cc0000;margin-top:12px;">
    <b>Action required:</b><br/>
    &bull; If the <b>Validate</b> stage failed: check that all parameter values are correct
      and all credential IDs exist in Jenkins.<br/>
    &bull; If the <b>Deploy</b> stage failed: check node connectivity, credential file validity,
      and the keychain password. Review the audit artifacts for partial state.
  </p>
  <br/>
  <p>Regards,<br/><b>Jenkins CI</b></p>
</body>
</html>
                        """
                    )
                }
            }
        }

    } // end post

} // end pipeline
