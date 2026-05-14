#!/usr/bin/env bash
# =============================================================================
# generate-dummy-signing.sh
#
# PURPOSE
#   Interactive tool to generate a self-signed iOS enterprise signing
#   environment for testing the codesign pipeline on macOS Tahoe (26)
#   WITHOUT an Apple Developer Account.
#
# USAGE
#   ./generate-dummy-signing.sh              # interactive menu (default)
#   ./generate-dummy-signing.sh --generate   # non-interactive: generate
#   ./generate-dummy-signing.sh --verify     # non-interactive: verify
#   ./generate-dummy-signing.sh --clean      # non-interactive: clean up
#   ./generate-dummy-signing.sh --identities # non-interactive: show certs
#
# REQUIREMENTS
#   macOS Tahoe (26)+, Xcode CLI tools, openssl (brew install openssl)
# =============================================================================
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
# Edit these values to match your project before running.
# --generate / menu option 1 will prompt you to confirm before proceeding.
TEAM_ID="DUMTEAM01"
TEAM_NAME="YourBank Ltd"
APP_NAME="BankNow"
BUNDLE_ID="com.yourbank.banknow"
CERT_CN="iPhone Distribution: ${TEAM_NAME} (${TEAM_ID})"
CERT_VALIDITY_DAYS=365
KEYCHAIN_NAME="ios-banknow-dummy.keychain"
KEYCHAIN_PASS="dummy-kc-$(date +%s | tail -c 8)"
CERT_PASS="DummyCertPass123!"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/dummy-signing"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m';  GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m';  BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'

log()     { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()     { echo -e "${RED}[ERROR]${NC} $*" >&2; }
section() { echo -e "\n${BOLD}${CYAN}── $* ──${NC}"; }
dim()     { echo -e "${DIM}$*${NC}"; }

# ── Helpers ───────────────────────────────────────────────────────────────────
require_cmd() {
    command -v "$1" &>/dev/null && return 0
    err "Required command not found: $1"
    case "$1" in
        openssl)  err "  Fix: brew install openssl" ;;
        uuidgen)  err "  Fix: available on macOS — check PATH" ;;
        security|codesign) err "  Fix: xcode-select --install" ;;
    esac
    return 1
}

check_prerequisites() {
    local missing=0
    for cmd in openssl security codesign uuidgen; do
        require_cmd "$cmd" || missing=$((missing+1))
    done
    [[ "${missing}" -eq 0 ]]
}

iso_date_plus_days() {
    local days=$1
    if date -v+"${days}d" +"%Y-%m-%dT%H:%M:%SZ" &>/dev/null 2>&1; then
        date -v+"${days}d" -u +"%Y-%m-%dT%H:%M:%SZ"
    else
        date -u -d "+${days} days" +"%Y-%m-%dT%H:%M:%SZ"
    fi
}

press_enter() {
    echo ""
    read -r -p "  Press Enter to continue..." _
}

confirm() {
    # confirm "Are you sure?" → returns 0 (yes) or 1 (no)
    local prompt="${1:-Are you sure?}"
    local answer
    echo ""
    read -r -p "  ${prompt} [y/N] " answer
    [[ "${answer}" =~ ^[Yy]$ ]]
}

# ── Banner ────────────────────────────────────────────────────────────────────
print_banner() {
    clear 2>/dev/null || true
    echo -e "${BOLD}${CYAN}"
    echo "  ╔══════════════════════════════════════════════════════════╗"
    echo "  ║     iOS Enterprise Signing Setup — macOS Tahoe (26)     ║"
    echo "  ║     Dummy certificate generator for pipeline testing     ║"
    echo "  ╚══════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

# ── Current config display ────────────────────────────────────────────────────
print_config() {
    echo -e "  ${BOLD}Current Configuration${NC}"
    echo    "  ─────────────────────────────────────────────────────────"
    printf  "  %-18s %s\n" "App Name:"     "${APP_NAME}"
    printf  "  %-18s %s\n" "Bundle ID:"    "${BUNDLE_ID}"
    printf  "  %-18s %s\n" "Team Name:"    "${TEAM_NAME}"
    printf  "  %-18s %s\n" "Team ID:"      "${TEAM_ID}"
    printf  "  %-18s %s\n" "Cert CN:"      "${CERT_CN}"
    printf  "  %-18s %s days\n" "Validity:" "${CERT_VALIDITY_DAYS}"
    printf  "  %-18s %s\n" "Keychain:"     "${KEYCHAIN_NAME}"
    printf  "  %-18s %s\n" "Output Dir:"   "${OUTPUT_DIR}"
    echo    "  ─────────────────────────────────────────────────────────"
    dim "  To change these values, edit the CONFIG section at the top of"
    dim "  this script: ${SCRIPT_DIR}/generate-dummy-signing.sh"
    echo ""
}

# ── Status badge ──────────────────────────────────────────────────────────────
print_status_badge() {
    local keychain_ok=false files_ok=false

    security find-identity -v -p codesigning "${KEYCHAIN_NAME}" \
        2>/dev/null | grep -q "iPhone Distribution" && keychain_ok=true

    [[ -f "${OUTPUT_DIR}/dist.p12" && \
       -f "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" ]] && files_ok=true

    echo -e "  ${BOLD}Status${NC}"
    echo    "  ─────────────────────────────────────────────────────────"
    if "${keychain_ok}"; then
        echo -e "  ${GREEN}●${NC} Signing identity  : present in keychain"
    else
        echo -e "  ${RED}●${NC} Signing identity  : NOT found"
    fi

    if "${files_ok}"; then
        echo -e "  ${GREEN}●${NC} Generated files   : present"
    else
        echo -e "  ${RED}●${NC} Generated files   : NOT found"
    fi
    echo ""
}

# ══════════════════════════════════════════════════════════════════════════════
# ACTION FUNCTIONS
# ══════════════════════════════════════════════════════════════════════════════

# ── Option 1: Generate ────────────────────────────────────────────────────────
do_generate() {
    print_banner
    echo -e "  ${BOLD}Generate Dummy Signing Environment${NC}\n"
    print_config

    if ! confirm "Proceed with the above configuration?"; then
        echo ""
        warn "Cancelled. Edit the CONFIG section at the top of the script and re-run."
        return
    fi

    section "Checking Prerequisites"
    if ! check_prerequisites; then
        err "One or more prerequisites are missing. Install them and retry."
        press_enter
        return
    fi
    ok "All prerequisites found."

    section "Preparing Output Directory"
    mkdir -p "${OUTPUT_DIR}"
    printf '%s' "${KEYCHAIN_PASS}" > "${OUTPUT_DIR}/.keychain_pass"
    chmod 600 "${OUTPUT_DIR}/.keychain_pass"
    log "Output: ${OUTPUT_DIR}"

    # ── CA ────────────────────────────────────────────────────────────────────
    section "Step 1 — Certificate Authority"
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
        -config "${OUTPUT_DIR}/ca.cnf" 2>/dev/null
    ok "Self-signed CA generated."

    # ── Distribution cert ─────────────────────────────────────────────────────
    section "Step 2 — iPhone Distribution Certificate"
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
keyUsage             = critical,digitalSignature
extendedKeyUsage     = critical,codeSigning
subjectKeyIdentifier = hash
EOF
    openssl genrsa -out "${OUTPUT_DIR}/dist.key" 2048 2>/dev/null
    openssl req -new \
        -key    "${OUTPUT_DIR}/dist.key" \
        -out    "${OUTPUT_DIR}/dist.csr" \
        -config "${OUTPUT_DIR}/dist.cnf" 2>/dev/null
    openssl x509 -req \
        -in         "${OUTPUT_DIR}/dist.csr" \
        -CA         "${OUTPUT_DIR}/ca.crt" \
        -CAkey      "${OUTPUT_DIR}/ca.key" \
        -CAcreateserial \
        -out        "${OUTPUT_DIR}/dist.crt" \
        -days       "${CERT_VALIDITY_DAYS}" \
        -extfile    "${OUTPUT_DIR}/dist.cnf" \
        -extensions v3_req 2>/dev/null
    ok "Distribution certificate generated."
    log "  CN     : $(openssl x509 -in "${OUTPUT_DIR}/dist.crt" -noout -subject 2>/dev/null | sed 's/subject=//')"
    log "  Expiry : $(openssl x509 -in "${OUTPUT_DIR}/dist.crt" -noout -enddate 2>/dev/null | sed 's/notAfter=//')"

    # ── PKCS#12 ───────────────────────────────────────────────────────────────
    section "Step 3 — PKCS#12 Bundle (.p12)"
    openssl pkcs12 -export \
        -out      "${OUTPUT_DIR}/dist.p12" \
        -inkey    "${OUTPUT_DIR}/dist.key" \
        -in       "${OUTPUT_DIR}/dist.crt" \
        -certfile "${OUTPUT_DIR}/ca.crt" \
        -passout  "pass:${CERT_PASS}" \
        -legacy 2>/dev/null || \
    openssl pkcs12 -export \
        -out      "${OUTPUT_DIR}/dist.p12" \
        -inkey    "${OUTPUT_DIR}/dist.key" \
        -in       "${OUTPUT_DIR}/dist.crt" \
        -certfile "${OUTPUT_DIR}/ca.crt" \
        -passout  "pass:${CERT_PASS}" 2>/dev/null
    ok "dist.p12 exported  (password: ${CERT_PASS})"

    # ── Keychain ──────────────────────────────────────────────────────────────
    section "Step 4 — Build Keychain"
    security delete-keychain "${KEYCHAIN_NAME}" 2>/dev/null || true
    security create-keychain -p "${KEYCHAIN_PASS}" "${KEYCHAIN_NAME}"
    security list-keychains -d user \
        -s "${KEYCHAIN_NAME}" $(security list-keychains -d user | tr -d '"' | tr '\n' ' ')
    security default-keychain -s "${KEYCHAIN_NAME}"
    security unlock-keychain  -p "${KEYCHAIN_PASS}" "${KEYCHAIN_NAME}"
    security set-keychain-settings -lut 7200 "${KEYCHAIN_NAME}"

    security import "${OUTPUT_DIR}/dist.p12" \
        -k "${KEYCHAIN_NAME}" \
        -P "${CERT_PASS}" \
        -T /usr/bin/codesign \
        -T /usr/bin/productbuild \
        -T /usr/bin/security \
        -f pkcs12

    # Tahoe fix: without set-key-partition-list, codesign throws
    # errSecInternalComponent on macOS Tahoe (26) under launchd agents.
    security set-key-partition-list \
        -S apple-tool:,apple:,codesign: \
        -s -k "${KEYCHAIN_PASS}" \
        "${KEYCHAIN_NAME}"
    ok "Keychain '${KEYCHAIN_NAME}' ready with Tahoe partition fix applied."

    # ── Trust the CA so security find-identity -v shows a VALID identity ─────
    # security find-identity -v only lists certs whose full chain is trusted.
    # Our self-signed CA is unknown to macOS by default, so without trusting it
    # the cert appears in the keychain but shows "0 valid identities found".
    # Adding the CA to the System keychain trust store fixes this.
    # Requires sudo — the Mac Mini agent must have passwordless sudo configured.
    log "Trusting CA in System keychain (requires sudo)..."
    if sudo security add-trusted-cert \
            -d -r trustRoot \
            -k /Library/Keychains/System.keychain \
            "${OUTPUT_DIR}/ca.crt" 2>/dev/null; then
        ok "CA trusted in System keychain — 'security find-identity -v' will now show valid identity."
    else
        warn "Could not add CA to System keychain (sudo may have prompted or been denied)."
        warn "Run manually: sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain dummy-signing/ca.crt"
        warn "Without this, 'security find-identity -v' shows 0 valid identities — but codesign"
        warn "may still work if you pass the keychain explicitly with --keychain flag."
    fi

    # ── Fake provisioning profile ─────────────────────────────────────────────
    section "Step 5 — Fake Enterprise Provisioning Profile"
    # Correct CMS structure — passes security cms -D and plutil checks.
    # NOT Apple-signed — will NOT install on device. For pipeline test only.
    PROFILE_UUID="$(uuidgen)"
    CREATION_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    EXPIRY_DATE="$(iso_date_plus_days "${CERT_VALIDITY_DAYS}")"
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
    <array><string>${TEAM_ID}</string></array>
    <key>CreationDate</key>
    <date>${CREATION_DATE}</date>
    <key>DeveloperCertificates</key>
    <array><data>${CERT_DER_B64}</data></array>
    <key>Entitlements</key>
    <dict>
        <key>application-identifier</key>
        <string>${TEAM_ID}.${BUNDLE_ID}</string>
        <key>com.apple.developer.team-identifier</key>
        <string>${TEAM_ID}</string>
        <key>get-task-allow</key>
        <false/>
        <key>keychain-access-groups</key>
        <array><string>${TEAM_ID}.*</string></array>
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
    <array><string>${TEAM_ID}</string></array>
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

    openssl smime -sign \
        -in       "${OUTPUT_DIR}/profile.plist" \
        -out      "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" \
        -signer   "${OUTPUT_DIR}/dist.crt" \
        -inkey    "${OUTPUT_DIR}/dist.key" \
        -certfile "${OUTPUT_DIR}/ca.crt" \
        -outform  DER -nodetach 2>/dev/null

    PROFILE_INSTALL_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
    mkdir -p "${PROFILE_INSTALL_DIR}"
    cp "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" \
       "${PROFILE_INSTALL_DIR}/${PROFILE_UUID}.mobileprovision"
    ok "Profile created and installed."
    log "  UUID    : ${PROFILE_UUID}"
    log "  Expires : ${EXPIRY_DATE}"

    # ── Entitlements.plist ────────────────────────────────────────────────────
    section "Step 6 — Entitlements.plist"
    cat > "${OUTPUT_DIR}/Entitlements.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>application-identifier</key>
    <string>${TEAM_ID}.${BUNDLE_ID}</string>
    <key>com.apple.developer.team-identifier</key>
    <string>${TEAM_ID}</string>
    <key>keychain-access-groups</key>
    <array>
        <string>${TEAM_ID}.${BUNDLE_ID}</string>
        <string>${TEAM_ID}.com.yourbank.shared</string>
    </array>
    <key>aps-environment</key>
    <string>production</string>
    <key>get-task-allow</key>
    <false/>
    <key>com.apple.developer.associated-domains</key>
    <array>
        <string>applinks:yourbank.com</string>
        <string>webcredentials:yourbank.com</string>
    </array>
</dict>
</plist>
PLIST
    ok "Entitlements.plist created."

    # ── ExportOptions (dummy) ─────────────────────────────────────────────────
    section "Step 7 — ExportOptions-dummy.plist (test reference)"
    cat > "${OUTPUT_DIR}/ExportOptions-dummy.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- TEST USE ONLY — Jenkinsfile bypasses -exportArchive for dummy builds -->
    <!-- and manually packages Payload/ as IPA instead.                        -->
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

    # ── ExportOptions (enterprise template) ───────────────────────────────────
    section "Step 8 — ExportOptions-enterprise.plist (production template)"
    cat > "${OUTPUT_DIR}/ExportOptions-enterprise.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- PRODUCTION TEMPLATE — replace REAL_TEAM_ID and profile name -->

    <key>method</key>
    <string>enterprise</string>

    <key>teamID</key>
    <string>REAL_TEAM_ID</string>

    <key>signingStyle</key>
    <string>manual</string>

    <key>signingCertificate</key>
    <string>iPhone Distribution</string>

    <key>provisioningProfiles</key>
    <dict>
        <key>${BUNDLE_ID}</key>
        <string>${TEAM_NAME} Enterprise Distribution</string>
        <!-- Extensions — add one entry per app extension:      -->
        <!-- <key>${BUNDLE_ID}.ShareExtension</key>             -->
        <!-- <string>Profile name for ShareExtension</string>   -->
    </dict>

    <key>compileBitcode</key>
    <false/>
    <key>stripSwiftSymbols</key>
    <true/>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
</dict>
</plist>
PLIST
    ok "ExportOptions-enterprise.plist (template) created."

    # ── signing-env.sh metadata ───────────────────────────────────────────────
    section "Step 9 — Metadata for Jenkinsfile"
    cat > "${OUTPUT_DIR}/signing-env.sh" << ENV
# Source this file in Jenkins sh blocks or teardown scripts:
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
    ok "signing-env.sh written."

    # ── Summary ───────────────────────────────────────────────────────────────
    echo ""
    echo -e "  ${BOLD}${GREEN}✓ Dummy signing environment ready${NC}"
    echo    "  ─────────────────────────────────────────────────────────"
    echo -e "  ${BOLD}Keychain:${NC}      ${KEYCHAIN_NAME}"
    echo -e "  ${BOLD}Identity:${NC}"
    security find-identity -v -p codesigning "${KEYCHAIN_NAME}" 2>/dev/null \
        | grep -v "^0 " | sed 's/^/    /' || true
    echo ""
    echo -e "  ${BOLD}Profile UUID:${NC}  ${PROFILE_UUID}"
    echo -e "  ${BOLD}Expires:${NC}       ${EXPIRY_DATE}"
    echo ""
    echo -e "  ${BOLD}Generated files:${NC}"
    ls -1 "${OUTPUT_DIR}/" | grep -v '^\.' | sed 's/^/    /'
    echo ""
    echo -e "  ${YELLOW}⚠  This cert is NOT Apple-trusted.  Pipeline testing only.${NC}"
    echo -e "  ${YELLOW}   The IPA cannot be installed on any device.${NC}"
    echo ""
    press_enter
}

# ── Option 2: Verify ──────────────────────────────────────────────────────────
do_verify() {
    print_banner
    echo -e "  ${BOLD}Verify Existing Setup${NC}\n"

    local pass=0 fail=0

    check_item() {
        local label="$1" result="$2"
        if [[ -n "${result}" ]]; then
            echo -e "  ${GREEN}✓${NC}  ${label}"
            pass=$((pass+1))
        else
            echo -e "  ${RED}✗${NC}  ${label}"
            fail=$((fail+1))
        fi
    }

    check_item "Output directory exists" \
        "$(ls "${OUTPUT_DIR}" 2>/dev/null | head -1)"
    check_item "CA certificate (ca.crt)" \
        "$(ls "${OUTPUT_DIR}/ca.crt" 2>/dev/null)"
    check_item "Distribution certificate (dist.crt)" \
        "$(ls "${OUTPUT_DIR}/dist.crt" 2>/dev/null)"
    check_item "PKCS#12 bundle (dist.p12)" \
        "$(ls "${OUTPUT_DIR}/dist.p12" 2>/dev/null)"
    check_item "Fake provisioning profile (${APP_NAME}.mobileprovision)" \
        "$(ls "${OUTPUT_DIR}/${APP_NAME}.mobileprovision" 2>/dev/null)"
    check_item "Entitlements.plist" \
        "$(ls "${OUTPUT_DIR}/Entitlements.plist" 2>/dev/null)"
    check_item "ExportOptions-dummy.plist" \
        "$(ls "${OUTPUT_DIR}/ExportOptions-dummy.plist" 2>/dev/null)"
    check_item "ExportOptions-enterprise.plist" \
        "$(ls "${OUTPUT_DIR}/ExportOptions-enterprise.plist" 2>/dev/null)"
    check_item "signing-env.sh metadata" \
        "$(ls "${OUTPUT_DIR}/signing-env.sh" 2>/dev/null)"

    # Check for the identity — use -a (all) not -v (valid only) so the check
    # passes even before the CA is added to the system trust store.
    # A separate line reports whether it is fully trusted (-v).
    local identity
    identity=$(security find-identity -a -p codesigning "${KEYCHAIN_NAME}" \
               2>/dev/null | grep "iPhone Distribution" | head -1 || echo "")
    check_item "Signing identity in keychain (${KEYCHAIN_NAME})" "${identity}"

    local valid_identity
    valid_identity=$(security find-identity -v -p codesigning "${KEYCHAIN_NAME}" \
                     2>/dev/null | grep "iPhone Distribution" | head -1 || echo "")
    if [[ -n "${valid_identity}" ]]; then
        echo -e "  ${GREEN}✓${NC}  CA trusted by macOS — identity is valid for codesigning"
        pass=$((pass+1))
    else
        echo -e "  ${YELLOW}!${NC}  CA not yet trusted — run: sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain dummy-signing/ca.crt"
    fi

    local profile_installed
    profile_installed=$(ls "${HOME}/Library/MobileDevice/Provisioning Profiles/"*.mobileprovision \
                        2>/dev/null | head -1 || echo "")
    check_item "Provisioning profile installed to MobileDevice directory" \
        "${profile_installed}"

    echo ""
    echo "  ─────────────────────────────────────────────────────────"
    if [[ "${fail}" -eq 0 ]]; then
        echo -e "  ${GREEN}${BOLD}All ${pass} checks passed.${NC}  Ready to use in Jenkins."
    else
        echo -e "  ${RED}${BOLD}${fail} check(s) failed${NC} (${pass} passed)."
        echo -e "  ${YELLOW}Run 'Generate dummy signing environment' to rebuild.${NC}"
    fi
    echo ""

    if [[ -n "${identity}" ]]; then
        echo -e "  ${BOLD}Identity:${NC}  ${identity}"
    fi
    if [[ -f "${OUTPUT_DIR}/signing-env.sh" ]]; then
        local uuid
        uuid=$(grep DUMMY_PROFILE_UUID "${OUTPUT_DIR}/signing-env.sh" \
               | cut -d= -f2 | tr -d '"' || echo "unknown")
        echo -e "  ${BOLD}Profile UUID:${NC}  ${uuid}"
    fi
    echo ""
    press_enter
}

# ── Option 3: Show Identities ─────────────────────────────────────────────────
do_show_identities() {
    print_banner
    echo -e "  ${BOLD}Signing Identities${NC}\n"

    echo -e "  ${BOLD}Dummy keychain (${KEYCHAIN_NAME}):${NC}"
    local dummy_ids
    dummy_ids=$(security find-identity -v -p codesigning "${KEYCHAIN_NAME}" \
                2>/dev/null || echo "")
    if [[ -n "${dummy_ids}" ]]; then
        echo "${dummy_ids}" | grep -v "^0 " | sed 's/^/    /'
    else
        echo -e "    ${DIM}(keychain not found or no identities)${NC}"
    fi

    echo ""
    echo -e "  ${BOLD}Login keychain (all codesigning identities):${NC}"
    local login_ids
    login_ids=$(security find-identity -v -p codesigning 2>/dev/null \
                | grep "iPhone\|Apple\|Developer" || echo "")
    if [[ -n "${login_ids}" ]]; then
        echo "${login_ids}" | sed 's/^/    /'
    else
        echo -e "    ${DIM}(none found)${NC}"
    fi

    echo ""
    press_enter
}

# ── Option 4: Show Installed Profiles ────────────────────────────────────────
do_show_profiles() {
    print_banner
    echo -e "  ${BOLD}Installed Provisioning Profiles${NC}\n"

    local PROFILE_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
    local count=0

    if [[ ! -d "${PROFILE_DIR}" ]]; then
        echo -e "  ${DIM}Directory not found: ${PROFILE_DIR}${NC}"
        press_enter
        return
    fi

    while IFS= read -r -d '' f; do
        count=$((count+1))
        local decoded
        decoded="$(mktemp "${TMPDIR:-/tmp}/profile-view.XXXXXX.plist")"
        # shellcheck disable=SC2064
        trap "rm -f '${decoded}'" RETURN

        if security cms -D -i "$f" -o "${decoded}" 2>/dev/null; then
            local name uuid expiry
            name=$(plutil -extract Name raw "${decoded}" 2>/dev/null || echo "(unknown)")
            uuid=$(plutil -extract UUID raw "${decoded}" 2>/dev/null || echo "(unknown)")
            expiry=$(plutil -extract ExpirationDate raw "${decoded}" 2>/dev/null || echo "unknown")

            # Expiry colouring
            local expiry_colour="${GREEN}"
            if [[ "${expiry}" != "unknown" ]]; then
                local expiry_epoch now_epoch days_left
                expiry_epoch=$(date -j -f "%Y-%m-%dT%H:%M:%SZ" "${expiry}" "+%s" 2>/dev/null \
                               || date -d "${expiry}" "+%s" 2>/dev/null || echo 0)
                now_epoch=$(date +%s)
                days_left=$(( (expiry_epoch - now_epoch) / 86400 ))
                if [[ "${days_left}" -lt 0 ]]; then
                    expiry_colour="${RED}"
                    expiry="${expiry} (EXPIRED)"
                elif [[ "${days_left}" -lt 30 ]]; then
                    expiry_colour="${YELLOW}"
                    expiry="${expiry} (${days_left}d left)"
                else
                    expiry="${expiry} (${days_left}d left)"
                fi
            fi

            echo -e "  ${BOLD}${count}. ${name}${NC}"
            printf  "     %-10s %s\n" "UUID:"    "${uuid}"
            printf  "     %-10s " "Expires:"
            echo -e "${expiry_colour}${expiry}${NC}"
            printf  "     %-10s %s\n" "File:" "$(basename "$f")"
            echo ""
        else
            echo -e "  ${count}. ${DIM}$(basename "$f") — could not decode${NC}\n"
        fi
        rm -f "${decoded}"
    done < <(find "${PROFILE_DIR}" -maxdepth 1 -name "*.mobileprovision" -print0 2>/dev/null)

    if [[ "${count}" -eq 0 ]]; then
        echo -e "  ${DIM}No provisioning profiles installed.${NC}"
    else
        echo -e "  ${BOLD}Total:${NC} ${count} profile(s)"
    fi
    echo ""
    press_enter
}

# ── Option 5: Clean Up ────────────────────────────────────────────────────────
do_clean() {
    print_banner
    echo -e "  ${BOLD}Clean Up${NC}\n"
    echo    "  This will delete:"
    echo -e "  ${RED}•${NC}  Keychain:     ${KEYCHAIN_NAME}"
    echo -e "  ${RED}•${NC}  Output dir:   ${OUTPUT_DIR}"
    echo -e "  ${RED}•${NC}  Profile from: ~/Library/MobileDevice/Provisioning Profiles/"
    echo    "  (only the profile installed by this script will be removed)"
    echo ""

    if ! confirm "Continue with cleanup?"; then
        echo ""
        warn "Cancelled — nothing was deleted."
        press_enter
        return
    fi

    echo ""
    # Remove keychain
    if security delete-keychain "${KEYCHAIN_NAME}" 2>/dev/null; then
        ok "Keychain '${KEYCHAIN_NAME}' deleted."
    else
        warn "Keychain not found — already removed."
    fi
    security list-keychains -d user -s login.keychain 2>/dev/null || true
    security default-keychain -s login.keychain 2>/dev/null || true

    # Remove installed profile (by UUID stored in signing-env.sh)
    if [[ -f "${OUTPUT_DIR}/signing-env.sh" ]]; then
        local uuid
        uuid=$(grep DUMMY_PROFILE_UUID "${OUTPUT_DIR}/signing-env.sh" \
               | cut -d= -f2 | tr -d '"' || echo "")
        local profile_path="${HOME}/Library/MobileDevice/Provisioning Profiles/${uuid}.mobileprovision"
        if [[ -n "${uuid}" && -f "${profile_path}" ]]; then
            rm -f "${profile_path}"
            ok "Provisioning profile removed (UUID: ${uuid})."
        else
            warn "Profile not found in MobileDevice directory (may already be removed)."
        fi
    fi

    # Remove output directory
    rm -rf "${OUTPUT_DIR}"
    ok "Output directory removed."

    echo ""
    ok "Clean up complete."
    press_enter
}

# ── Option 6: Show/Edit Config ────────────────────────────────────────────────
do_show_config() {
    print_banner
    echo -e "  ${BOLD}Configuration${NC}\n"
    print_config
    echo    "  To change any value, open this file in your editor:"
    echo -e "  ${CYAN}  ${SCRIPT_DIR}/generate-dummy-signing.sh${NC}"
    echo ""
    echo    "  Look for the '── Configuration ──' section near the top."
    echo    "  After editing, re-run the script and choose option 1 to regenerate."
    echo ""
    press_enter
}

# ══════════════════════════════════════════════════════════════════════════════
# MAIN MENU
# ══════════════════════════════════════════════════════════════════════════════
show_menu() {
    while true; do
        print_banner
        print_status_badge

        echo -e "  ${BOLD}What would you like to do?${NC}"
        echo ""
        echo    "   1)  Generate dummy signing environment"
        echo    "       (creates cert, keychain, profile, plists)"
        echo ""
        echo    "   2)  Verify existing setup"
        echo    "       (check all files and keychain are present)"
        echo ""
        echo    "   3)  Show signing identities"
        echo    "       (list certs installed in keychains)"
        echo ""
        echo    "   4)  Show installed provisioning profiles"
        echo    "       (list profiles with name, UUID, expiry)"
        echo ""
        echo    "   5)  Clean up"
        echo    "       (remove keychain and all generated files)"
        echo ""
        echo    "   6)  Show / change configuration"
        echo    "       (app name, bundle ID, team, keychain name)"
        echo ""
        echo    "   7)  Exit"
        echo ""
        echo    "  ─────────────────────────────────────────────────────────"
        read -r -p "  Enter choice [1-7]: " choice
        echo ""

        case "${choice}" in
            1) do_generate      ;;
            2) do_verify        ;;
            3) do_show_identities ;;
            4) do_show_profiles ;;
            5) do_clean         ;;
            6) do_show_config   ;;
            7)
                echo -e "  ${DIM}Bye.${NC}"
                echo ""
                exit 0
                ;;
            *)
                warn "Invalid choice '${choice}' — enter a number from 1 to 7."
                sleep 1
                ;;
        esac
    done
}

# ══════════════════════════════════════════════════════════════════════════════
# ENTRY POINT
# Non-interactive flags kept for Jenkins pipeline calls:
#   --generate | --verify | --clean | --identities
# No args → interactive menu
# ══════════════════════════════════════════════════════════════════════════════
case "${1:-menu}" in
    --generate)   do_generate        ;;
    --verify)     do_verify          ;;
    --clean)      do_clean           ;;
    --identities) do_show_identities ;;
    menu)         show_menu          ;;
    *)
        err "Unknown argument: $1"
        echo ""
        echo "Usage:"
        echo "  ./generate-dummy-signing.sh              # interactive menu"
        echo "  ./generate-dummy-signing.sh --generate   # generate (non-interactive)"
        echo "  ./generate-dummy-signing.sh --verify     # verify   (non-interactive)"
        echo "  ./generate-dummy-signing.sh --clean      # clean up (non-interactive)"
        echo "  ./generate-dummy-signing.sh --identities # show identities"
        exit 1
        ;;
esac
