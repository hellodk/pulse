#!/usr/bin/env bash
# =============================================================================
# generate-dummy-signing.sh
#
# PURPOSE
#   Generates a complete self-signed iOS enterprise signing environment for
#   testing the codesign pipeline on macOS Tahoe (26) WITHOUT an Apple
#   Developer Account.  Produces a real, signed .ipa file.
#
# WHAT IT CREATES
#   dummy-signing/
#   ├── ca.key / ca.crt              — self-signed CA (root)
#   ├── dist.key / dist.crt          — "iPhone Distribution" leaf cert
#   ├── dist.p12                     — PKCS#12 bundle (cert + key)
#   ├── BankNow.mobileprovision      — CMS-signed fake enterprise profile
#   ├── Entitlements.plist           — entitlements for codesign
#   ├── ExportOptions-dummy.plist    — for dummy/test IPA packaging
#   └── ExportOptions-enterprise.plist — TEMPLATE for real org use
#
# KEYCHAIN
#   Creates a dedicated keychain: ios-banknow-dummy.keychain
#   Includes Tahoe fix: security set-key-partition-list
#   Cleaned up automatically on re-run; manually via --clean flag.
#
# IPA OUTPUT
#   The pipeline (Jenkinsfile.groovy) calls this script, then archives
#   and manually packages the IPA — bypassing -exportArchive which requires
#   an Apple-signed provisioning profile.  Switch DUMMY_SIGNING=false in
#   Jenkins to use real enterprise credentials in production.
#
# USAGE
#   chmod +x generate-dummy-signing.sh
#   ./generate-dummy-signing.sh            # generate everything
#   ./generate-dummy-signing.sh --clean    # remove keychain + output dir
#   ./generate-dummy-signing.sh --verify   # verify existing setup
#
# REQUIREMENTS
#   macOS Tahoe (26) or later, Xcode CLI tools, openssl (Homebrew recommended)
# =============================================================================
set -euo pipefail

# ── Configuration — edit these to match your real project ────────────────────
TEAM_ID="DUMTEAM01"
TEAM_NAME="YourBank Ltd"
APP_NAME="BankNow"
BUNDLE_ID="com.yourbank.banknow"
CERT_CN="iPhone Distribution: ${TEAM_NAME} (${TEAM_ID})"
CERT_VALIDITY_DAYS=365
KEYCHAIN_NAME="ios-banknow-dummy.keychain"
KEYCHAIN_PASS="dummy-kc-pass-$(date +%s | tail -c 8)"   # ephemeral; stored in output dir
CERT_PASS="DummyCertPass123!"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/dummy-signing"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()     { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()   { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()    { echo -e "${RED}[ERROR]${NC} $*" >&2; }
section(){ echo -e "\n${BOLD}${CYAN}══ $* ══${NC}"; }

# ── Helpers ───────────────────────────────────────────────────────────────────
require_cmd() {
    if ! command -v "$1" &>/dev/null; then
        err "Required command not found: $1"
        case "$1" in
            openssl) err "Install via: brew install openssl" ;;
            uuidgen) err "Available on macOS — check PATH" ;;
            security|codesign|xcodebuild)
                err "Install Xcode Command Line Tools: xcode-select --install" ;;
        esac
        exit 1
    fi
}

iso_date_plus_days() {
    local days=$1
    if date -v+"${days}d" +"%Y-%m-%dT%H:%M:%SZ" &>/dev/null 2>&1; then
        date -v+"${days}d" -u +"%Y-%m-%dT%H:%M:%SZ"   # macOS BSD date
    else
        date -u -d "+${days} days" +"%Y-%m-%dT%H:%M:%SZ"  # GNU date fallback
    fi
}

# ── Clean ─────────────────────────────────────────────────────────────────────
do_clean() {
    section "Cleaning Up"
    security delete-keychain "${KEYCHAIN_NAME}" 2>/dev/null \
        && ok "Keychain '${KEYCHAIN_NAME}' deleted." \
        || warn "Keychain not found — nothing to delete."
    rm -rf "${OUTPUT_DIR}"
    ok "Output directory removed: ${OUTPUT_DIR}"
    exit 0
}

# ── Verify ────────────────────────────────────────────────────────────────────
do_verify() {
    section "Verifying Existing Setup"
    local ok_count=0 fail_count=0

    check() {
        local label="$1" val="$2"
        if [[ -n "${val}" && "${val}" != "(none found)" ]]; then
            ok "${label}"
            (( ok_count++ )) || true
        else
            err "${label}: NOT FOUND"
            (( fail_count++ )) || true
        fi
    }

    check "Output directory" "$(ls "${OUTPUT_DIR}" 2>/dev/null | head -1)"
    check "dist.p12"         "$(ls "${OUTPUT_DIR}/dist.p12" 2>/dev/null)"
    check "BankNow.mobileprovision" \
          "$(ls "${OUTPUT_DIR}/BankNow.mobileprovision" 2>/dev/null)"
    check "ExportOptions-dummy.plist" \
          "$(ls "${OUTPUT_DIR}/ExportOptions-dummy.plist" 2>/dev/null)"

    local identity
    identity=$(security find-identity -v -p codesigning "${KEYCHAIN_NAME}" \
               2>/dev/null | grep "iPhone Distribution" | head -1 || echo "")
    check "Signing identity in keychain" "${identity}"
    if [[ -n "${identity}" ]]; then
        log "  ${identity}"
    fi

    echo ""
    if [[ "${fail_count}" -eq 0 ]]; then
        ok "All checks passed (${ok_count}/${ok_count})"
    else
        err "${fail_count} check(s) failed — re-run without --verify to regenerate"
        exit 1
    fi
    exit 0
}

# ── Argument parsing ──────────────────────────────────────────────────────────
[[ "${1:-}" == "--clean"  ]] && do_clean
[[ "${1:-}" == "--verify" ]] && do_verify

# ── Prerequisites ─────────────────────────────────────────────────────────────
section "Checking Prerequisites"
require_cmd openssl
require_cmd security
require_cmd codesign
require_cmd uuidgen
ok "All prerequisites found."

# ── Setup ─────────────────────────────────────────────────────────────────────
section "Preparing Output Directory"
mkdir -p "${OUTPUT_DIR}"
log "Output dir : ${OUTPUT_DIR}"
log "Team ID    : ${TEAM_ID}"
log "Bundle ID  : ${BUNDLE_ID}"
log "Cert CN    : ${CERT_CN}"
log "Validity   : ${CERT_VALIDITY_DAYS} days"

# Save keychain pass for the teardown script / Jenkins post-always
printf '%s' "${KEYCHAIN_PASS}" > "${OUTPUT_DIR}/.keychain_pass"
chmod 600 "${OUTPUT_DIR}/.keychain_pass"

# ── Step 1: Self-Signed CA ────────────────────────────────────────────────────
section "Step 1 — Generating Certificate Authority"

cat > "${OUTPUT_DIR}/ca.cnf" << EOF
[req]
default_bits       = 4096
distinguished_name = dn
x509_extensions    = v3_ca
prompt             = no

[dn]
C  = US
ST = California
L  = Cupertino
O  = ${TEAM_NAME} Internal CA
CN = ${TEAM_NAME} Root CA

[v3_ca]
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints       = critical,CA:true
keyUsage               = critical,keyCertSign,cRLSign
EOF

openssl genrsa -out "${OUTPUT_DIR}/ca.key" 4096 2>/dev/null
openssl req -new -x509 \
    -key    "${OUTPUT_DIR}/ca.key" \
    -out    "${OUTPUT_DIR}/ca.crt" \
    -days   "${CERT_VALIDITY_DAYS}" \
    -config "${OUTPUT_DIR}/ca.cnf" \
    2>/dev/null
ok "CA certificate generated."

# ── Step 2: Distribution Certificate ─────────────────────────────────────────
section "Step 2 — Generating iPhone Distribution Certificate"

cat > "${OUTPUT_DIR}/dist.cnf" << EOF
[req]
default_bits       = 2048
distinguished_name = dn
req_extensions     = v3_req
prompt             = no

[dn]
C  = US
ST = California
L  = Cupertino
O  = ${TEAM_NAME}
CN = ${CERT_CN}

[v3_req]
keyUsage         = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
subjectKeyIdentifier = hash
EOF

openssl genrsa -out "${OUTPUT_DIR}/dist.key" 2048 2>/dev/null

openssl req -new \
    -key    "${OUTPUT_DIR}/dist.key" \
    -out    "${OUTPUT_DIR}/dist.csr" \
    -config "${OUTPUT_DIR}/dist.cnf" \
    2>/dev/null

openssl x509 -req \
    -in         "${OUTPUT_DIR}/dist.csr" \
    -CA         "${OUTPUT_DIR}/ca.crt" \
    -CAkey      "${OUTPUT_DIR}/ca.key" \
    -CAcreateserial \
    -out        "${OUTPUT_DIR}/dist.crt" \
    -days       "${CERT_VALIDITY_DAYS}" \
    -extfile    "${OUTPUT_DIR}/dist.cnf" \
    -extensions v3_req \
    2>/dev/null

ok "Distribution certificate generated."
log "Subject: $(openssl x509 -in "${OUTPUT_DIR}/dist.crt" -noout -subject 2>/dev/null)"
log "Expiry : $(openssl x509 -in "${OUTPUT_DIR}/dist.crt" -noout -enddate 2>/dev/null)"

# ── Step 3: PKCS#12 Bundle ────────────────────────────────────────────────────
section "Step 3 — Exporting .p12 Bundle"

openssl pkcs12 -export \
    -out        "${OUTPUT_DIR}/dist.p12" \
    -inkey      "${OUTPUT_DIR}/dist.key" \
    -in         "${OUTPUT_DIR}/dist.crt" \
    -certfile   "${OUTPUT_DIR}/ca.crt" \
    -passout    "pass:${CERT_PASS}" \
    -legacy \
    2>/dev/null || \
openssl pkcs12 -export \
    -out        "${OUTPUT_DIR}/dist.p12" \
    -inkey      "${OUTPUT_DIR}/dist.key" \
    -in         "${OUTPUT_DIR}/dist.crt" \
    -certfile   "${OUTPUT_DIR}/ca.crt" \
    -passout    "pass:${CERT_PASS}" \
    2>/dev/null

ok "dist.p12 created (password: ${CERT_PASS})"

# ── Step 4: Keychain ──────────────────────────────────────────────────────────
section "Step 4 — Setting Up Dedicated Build Keychain"

# Remove stale keychain from a previous run
security delete-keychain "${KEYCHAIN_NAME}" 2>/dev/null || true

security create-keychain -p "${KEYCHAIN_PASS}" "${KEYCHAIN_NAME}"

# Add to keychain search list
security list-keychains -d user \
    -s "${KEYCHAIN_NAME}" $(security list-keychains -d user | tr -d '"' | tr '\n' ' ')

security default-keychain -s "${KEYCHAIN_NAME}"
security unlock-keychain  -p "${KEYCHAIN_PASS}" "${KEYCHAIN_NAME}"
security set-keychain-settings -lut 7200 "${KEYCHAIN_NAME}"   # 2h timeout

log "Importing distribution certificate..."
security import "${OUTPUT_DIR}/dist.p12" \
    -k "${KEYCHAIN_NAME}" \
    -P "${CERT_PASS}" \
    -T /usr/bin/codesign \
    -T /usr/bin/productbuild \
    -T /usr/bin/security \
    -f pkcs12

# ── Tahoe fix: set-key-partition-list ─────────────────────────────────────────
# Without this, codesign throws errSecInternalComponent on macOS Tahoe (26).
# The -s -k flag uses the keychain password (not the cert password).
log "Applying codesign partition list (Tahoe fix)..."
security set-key-partition-list \
    -S apple-tool:,apple:,codesign: \
    -s -k "${KEYCHAIN_PASS}" \
    "${KEYCHAIN_NAME}"

ok "Keychain '${KEYCHAIN_NAME}' ready."
log "Installed identity:"
security find-identity -v -p codesigning "${KEYCHAIN_NAME}" | grep "iPhone Distribution" || \
    warn "No iPhone Distribution identity found — check cert CN matches expected format."

# ── Step 5: Fake Enterprise Provisioning Profile ──────────────────────────────
section "Step 5 — Generating Fake Enterprise Provisioning Profile"
# NOTE: A real .mobileprovision is CMS-signed by Apple's servers.
# This fake profile has the correct structure and is CMS-signed by our
# own CA.  It will pass structural checks (security cms -D, plutil) but
# NOT Apple's trust chain verification.  Xcode will reject it at install
# time — but the BUILD pipeline steps (UUID extraction, profile copy,
# xcodebuild archive) will all work correctly, which is the test goal.

PROFILE_UUID="$(uuidgen)"
CREATION_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
EXPIRY_DATE="$(iso_date_plus_days ${CERT_VALIDITY_DAYS})"
CERT_DER_B64="$(openssl x509 -in "${OUTPUT_DIR}/dist.crt" -outform DER 2>/dev/null | base64)"

cat > "${OUTPUT_DIR}/profile.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>AppIDName</key>
    <string>${TEAM_NAME} Mobile Banking</string>
    <key>ApplicationIdentifierPrefix</key>
    <array>
        <string>${TEAM_ID}</string>
    </array>
    <key>CreationDate</key>
    <date>${CREATION_DATE}</date>
    <key>DeveloperCertificates</key>
    <array>
        <data>${CERT_DER_B64}</data>
    </array>
    <key>Entitlements</key>
    <dict>
        <key>application-identifier</key>
        <string>${TEAM_ID}.${BUNDLE_ID}</string>
        <key>com.apple.developer.team-identifier</key>
        <string>${TEAM_ID}</string>
        <key>get-task-allow</key>
        <false/>
        <key>keychain-access-groups</key>
        <array>
            <string>${TEAM_ID}.*</string>
        </array>
        <key>aps-environment</key>
        <string>production</string>
    </dict>
    <key>ExpirationDate</key>
    <date>${EXPIRY_DATE}</date>
    <key>Name</key>
    <string>${TEAM_NAME} Enterprise Distribution</string>
    <key>ProvisionsAllDevices</key>
    <true/>
    <key>TeamIdentifier</key>
    <array>
        <string>${TEAM_ID}</string>
    </array>
    <key>TeamName</key>
    <string>${TEAM_NAME}</string>
    <key>TimeToLive</key>
    <integer>${CERT_VALIDITY_DAYS}</integer>
    <key>UUID</key>
    <string>${PROFILE_UUID}</string>
    <key>Version</key>
    <integer>1</integer>
</dict>
</plist>
PLIST

# CMS-sign the plist (mirrors the structure of a real mobileprovision)
openssl smime -sign \
    -in       "${OUTPUT_DIR}/profile.plist" \
    -out      "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" \
    -signer   "${OUTPUT_DIR}/dist.crt" \
    -inkey    "${OUTPUT_DIR}/dist.key" \
    -certfile "${OUTPUT_DIR}/ca.crt" \
    -outform  DER \
    -nodetach \
    2>/dev/null

ok "Fake provisioning profile created."
log "  UUID : ${PROFILE_UUID}"
log "  Name : ${TEAM_NAME} Enterprise Distribution"
log "  Exp  : ${EXPIRY_DATE}"

# Install to MobileDevice profiles directory (Xcode looks here)
PROFILE_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
mkdir -p "${PROFILE_DIR}"
cp "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" \
   "${PROFILE_DIR}/${PROFILE_UUID}.mobileprovision"
ok "Profile installed to: ${PROFILE_DIR}/${PROFILE_UUID}.mobileprovision"

# ── Step 6: Entitlements.plist ────────────────────────────────────────────────
section "Step 6 — Creating Entitlements.plist"

cat > "${OUTPUT_DIR}/Entitlements.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Team and app identity -->
    <key>application-identifier</key>
    <string>${TEAM_ID}.${BUNDLE_ID}</string>
    <key>com.apple.developer.team-identifier</key>
    <string>${TEAM_ID}</string>

    <!-- Keychain sharing — required for banking apps -->
    <key>keychain-access-groups</key>
    <array>
        <string>${TEAM_ID}.${BUNDLE_ID}</string>
        <string>${TEAM_ID}.com.yourbank.shared</string>
    </array>

    <!-- Push notifications (production for enterprise) -->
    <key>aps-environment</key>
    <string>production</string>

    <!-- Distribution build — no debugger attach allowed -->
    <key>get-task-allow</key>
    <false/>

    <!-- Associated domains (replace with your real domains in prod) -->
    <key>com.apple.developer.associated-domains</key>
    <array>
        <string>applinks:yourbank.com</string>
        <string>webcredentials:yourbank.com</string>
    </array>
</dict>
</plist>
PLIST
ok "Entitlements.plist created."

# ── Step 7: ExportOptions — Dummy (test use) ──────────────────────────────────
section "Step 7 — Creating ExportOptions-dummy.plist"
# Uses signingCertificate="-" (ad-hoc) because -exportArchive with
# method:enterprise requires an Apple-signed provisioning profile.
# The Jenkinsfile bypasses -exportArchive and manually packages the IPA instead.
# This file is kept for reference and documentation purposes.

cat > "${OUTPUT_DIR}/ExportOptions-dummy.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Dummy/test use only — no Apple Developer Account required -->
    <!-- In production replace with ExportOptions-enterprise.plist  -->
    <key>method</key>
    <string>ad-hoc</string>
    <key>signingStyle</key>
    <string>manual</string>
    <key>signingCertificate</key>
    <string>${CERT_CN}</string>
    <key>teamID</key>
    <string>${TEAM_ID}</string>
    <key>compileBitcode</key>
    <false/>
    <key>stripSwiftSymbols</key>
    <true/>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
</dict>
</plist>
PLIST
ok "ExportOptions-dummy.plist created."

# ── Step 8: ExportOptions — Enterprise (production template) ──────────────────
section "Step 8 — Creating ExportOptions-enterprise.plist (production template)"
# Replace REAL_TEAM_ID, profile name, and bundle ID with your org's values.

cat > "${OUTPUT_DIR}/ExportOptions-enterprise.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- ═══ PRODUCTION TEMPLATE — fill in real values before use ═══ -->

    <key>method</key>
    <string>enterprise</string>

    <!-- Your Apple Developer Enterprise Program Team ID -->
    <key>teamID</key>
    <string>REAL_TEAM_ID</string>

    <key>signingStyle</key>
    <string>manual</string>

    <key>signingCertificate</key>
    <string>iPhone Distribution</string>

    <!-- Maps bundle ID → provisioning profile name in Apple portal -->
    <key>provisioningProfiles</key>
    <dict>
        <key>${BUNDLE_ID}</key>
        <string>${TEAM_NAME} Enterprise Distribution</string>
        <!-- Add extensions here if your app has any:           -->
        <!-- <key>${BUNDLE_ID}.NotificationExtension</key>      -->
        <!-- <string>Profile Name for Extension</string>        -->
    </dict>

    <key>compileBitcode</key>
    <false/>

    <key>stripSwiftSymbols</key>
    <true/>

    <!-- <none> = no app thinning; use device model for slimmer IPA -->
    <key>thinning</key>
    <string>&lt;none&gt;</string>

    <!-- Uncomment for on-demand resources:                     -->
    <!-- <key>embedOnDemandResourcesAssetPacksInBundle</key>    -->
    <!-- <true/>                                                -->
</dict>
</plist>
PLIST
ok "ExportOptions-enterprise.plist (template) created."

# ── Step 9: Metadata file for Jenkins / teardown ──────────────────────────────
section "Step 9 — Writing Metadata"

cat > "${OUTPUT_DIR}/signing-env.sh" << ENV
# Source this in the Jenkinsfile sh block or teardown script:
#   source dummy-signing/signing-env.sh
export DUMMY_KEYCHAIN_NAME="${KEYCHAIN_NAME}"
export DUMMY_KEYCHAIN_PASS="${KEYCHAIN_PASS}"
export DUMMY_CERT_PASS="${CERT_PASS}"
export DUMMY_CERT_CN="${CERT_CN}"
export DUMMY_TEAM_ID="${TEAM_ID}"
export DUMMY_BUNDLE_ID="${BUNDLE_ID}"
export DUMMY_PROFILE_UUID="${PROFILE_UUID}"
export DUMMY_OUTPUT_DIR="${OUTPUT_DIR}"
ENV
chmod 600 "${OUTPUT_DIR}/signing-env.sh"
ok "signing-env.sh written (sourced by Jenkinsfile)."

# ── Final Summary ─────────────────────────────────────────────────────────────
section "Summary"
echo ""
echo -e "${BOLD}Generated files:${NC}"
ls -lh "${OUTPUT_DIR}/" | grep -v '^total' | awk '{printf "  %-35s %s\n", $NF, $5}'
echo ""
echo -e "${BOLD}Keychain:${NC}   ${KEYCHAIN_NAME}"
echo -e "${BOLD}Identity:${NC}"
security find-identity -v -p codesigning "${KEYCHAIN_NAME}" 2>/dev/null \
    | grep -v "^0 " | sed 's/^/  /' || true
echo ""
echo -e "${BOLD}Profile UUID:${NC}  ${PROFILE_UUID}"
echo -e "${BOLD}Expires:${NC}       ${EXPIRY_DATE}"
echo ""
warn "This certificate is NOT trusted by Apple and cannot be used to install"
warn "the IPA on any device.  It is for PIPELINE TESTING ONLY."
echo ""
ok "Dummy signing environment ready."
ok "Run the Jenkins pipeline with DUMMY_SIGNING=true to build the test IPA."
echo ""
echo -e "${CYAN}Cleanup when done:${NC}  ./generate-dummy-signing.sh --clean"
echo -e "${CYAN}Verify setup:${NC}       ./generate-dummy-signing.sh --verify"
echo ""
