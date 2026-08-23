#!/usr/bin/env bash
# =============================================================================
# sync-openbao-to-jenkins.sh — one-way sync of iOS signing material
#
#   OpenBao KV  ──►  Jenkins-b credentials (REST API)
#
# Source of truth : OpenBao KV v2  (default path: secret/jenkins/ios-signing)
#                   keys: p12_b64 | p12_pass
# Destination     : jenkins-b system credentials
#                   ios-dummy-p12      (secret file, dist.p12)
#                   ios-dummy-p12-pass (secret text)
#
# Required env:
#   BAO_TOKEN                 OpenBao token with read on the KV path
#   JENKINS_ADMIN_PASSWORD    jenkins-b admin password
#
# Optional env:
#   BAO_ADDR        (default http://127.0.0.1:8200 — use scripts/pulse bao-proxy)
#   BAO_PATH        (default secret/jenkins/ios-signing)
#   JENKINS_URL     (default http://192.168.1.10:30880)
#
# Usage:
#   ./sync-openbao-to-jenkins.sh            # sync both credentials
#   ./sync-openbao-to-jenkins.sh --verify   # only check creds exist in Jenkins
# =============================================================================
set -euo pipefail

BAO_ADDR="${BAO_ADDR:-http://127.0.0.1:8200}"
BAO_PATH="${BAO_PATH:-secret/jenkins/ios-signing}"
JENKINS_URL="${JENKINS_URL:-http://192.168.1.10:30880}"
CERT_ID="ios-dummy-p12"
PASS_ID="ios-dummy-p12-pass"

log() { echo "[INFO] $*"; }
ok()  { echo "[OK]   $*"; }
err() { echo "[ERROR] $*" >&2; exit 1; }

[[ -n "${JENKINS_ADMIN_PASSWORD:-}" ]] || err "JENKINS_ADMIN_PASSWORD env var required"
AUTH="admin:${JENKINS_ADMIN_PASSWORD}"

# ── OpenBao read (HTTP API — no CLI dependency) ──────────────────────────────
bao_read() {
    local field="$1"
    local mount="${BAO_PATH%%/*}" rest="${BAO_PATH#*/}"
    [[ -n "${BAO_TOKEN:-${VAULT_TOKEN:-}}" ]] || err "BAO_TOKEN env var required"
    BAO_FIELD="$field" curl -sf --max-time 10 \
        -H "X-Vault-Token: ${BAO_TOKEN:-${VAULT_TOKEN:-}}" \
        "${BAO_ADDR}/v1/${mount}/data/${rest}" \
    | python3 -c "
import json, os, sys
field = os.environ['BAO_FIELD']
d = json.load(sys.stdin)
sys.stdout.write(d.get('data', {}).get('data', {}).get(field, ''))
" || err "OpenBao read failed: ${BAO_PATH} field=${field} (token? port-forward running?)"
}

# ── Jenkins helpers ───────────────────────────────────────────────────────────
COOKIE_JAR="$(mktemp /tmp/.jenkins-cookies.XXXXXX)"
trap 'rm -f "$COOKIE_JAR"' EXIT

crumb() {
    # crumb is bound to the HTTP session — fetch and reuse the same cookie jar
    curl -sf -c "$COOKIE_JAR" -u "$AUTH" "${JENKINS_URL}/crumbIssuer/api/json" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['crumb'])"
}

delete_cred() {
    curl -s -o /dev/null -b "$COOKIE_JAR" -u "$AUTH" -X POST \
        "${JENKINS_URL}/credentials/store/system/domain/_/credential/$1/doDelete" \
        || true   # 404 on first run is fine
}

build_json() {
    # build_json <python-class> <extra-key-args...> ; values from env
    CREDS_CLASS="$1" shift
    python3 - "$@" <<'PY'
import json, os, sys
cls = os.environ["CREDS_CLASS"]
args = dict(a.split("=", 1) for a in sys.argv[1:])
creds = {"$class": cls, "scope": "GLOBAL"}
creds.update(args)
print(json.dumps({"": "0", "credentials": creds}))
PY
}

create_cred() {
    local id="$1" json_payload="$2"
    log "Creating credential: ${id}"
    delete_cred "$id"
    local c
    c="$(crumb)" || err "could not fetch CSRF crumb from ${JENKINS_URL}"
    # json must arrive as a FORM field — a raw application/json body 400s
    echo "$json_payload" | curl -sf -o /dev/null -b "$COOKIE_JAR" -u "$AUTH" -X POST \
        -H "Jenkins-Crumb: ${c}" \
        --data-urlencode "json=@-" \
        "${JENKINS_URL}/credentials/store/system/domain/_/createCredentials" \
        || err "creating ${id} failed"
    ok "created ${id}"
}

verify() {
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
        "${JENKINS_URL}/credentials/store/system/domain/_/credential/$1/" || true)
    [[ "$code" == "200" ]] && { ok "credential '$1' present"; return 0; }
    { echo "[MISS] credential '$1' (HTTP $code)"; return 1; }
}

# ── Main ─────────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--verify" ]]; then
    verify "$CERT_ID"; verify "$PASS_ID"
    exit 0
fi

log "Reading ${BAO_PATH} from ${BAO_ADDR}"
P12_B64="$(bao_read p12_b64)"
P12_PASS="$(bao_read p12_pass)"
[[ -n "$P12_B64"  ]] || err "p12_b64 empty in OpenBao"
[[ -n "$P12_PASS" ]] || err "p12_pass empty in OpenBao"

export CERT_ID PASS_ID P12_B64 P12_PASS BAO_PATH

PASS_JSON="$(build_json com.cloudbees.plugins.credentials.impl.StringCredentialsImpl \
    "id=${PASS_ID}" "secret=${P12_PASS}" \
    "description=Dummy signing cert password (source: OpenBao ${BAO_PATH})")"

FILE_JSON="$(CERT_ID="$CERT_ID" P12_B64="$P12_B64" DESC="Dummy signing identity p12 (source: OpenBao ${BAO_PATH})" \
    python3 -c '
import json, os
print(json.dumps({"": "0", "credentials": {
    "$class": "org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl",
    "scope": "GLOBAL",
    "id": os.environ["CERT_ID"],
    "fileName": "dist.p12",
    "bytes": os.environ["P12_B64"],
    "description": os.environ["DESC"],
}}))')"

create_cred "$PASS_ID" "$PASS_JSON"
create_cred "$CERT_ID" "$FILE_JSON"

log "Verifying..."
verify "$PASS_ID" && verify "$CERT_ID"
ok "sync complete: OpenBao ${BAO_PATH} → jenkins-b credentials"
