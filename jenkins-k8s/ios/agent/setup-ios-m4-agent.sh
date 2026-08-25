#!/usr/bin/env bash
# =============================================================================
# setup-ios-m4-agent.sh — install the jenkins-b inbound agent on the M4 Mac mini
#
# Creates a SECOND, isolated agent instance (node "ios-m4") pointing at
# jenkins-b. The existing mobileapp-m3 agent (jenkins-c) is untouched.
#
# Usage (run on the Mac as user dk):
#   AGENT_SECRET=<secret> ./setup-ios-m4-agent.sh
# Or from the laptop via scripts/pulse:  scripts/pulse agent install ios-m4
#
# The secret comes from Jenkins: /computer/ios-m4/slave-agent.jnlp (admin auth).
# Never hardcode it here.
# =============================================================================
set -euo pipefail

AGENT_NAME="ios-m4"
AGENT_URL="${AGENT_URL:-http://192.168.1.10:30880/}"
WORK_DIR="$HOME/jenkins-agent-b"
PLIST_LABEL="com.jenkins.${AGENT_NAME}"
PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"
JAVA_BIN="/opt/homebrew/opt/openjdk@21/bin/java"

log()  { echo "[INFO] $*"; }
ok()   { echo "[OK]   $*"; }
err()  { echo "[ERROR] $*" >&2; exit 1; }

[[ -n "${AGENT_SECRET:-}" ]] || err "AGENT_SECRET env var required (no hardcoded secrets)"
command -v python3 >/dev/null || err "python3 not found"
export AGENT_NAME AGENT_URL JAVA_BIN

# ── Java ─────────────────────────────────────────────────────────────────────
[[ -x "$JAVA_BIN" ]] || err "Java 21 missing at $JAVA_BIN (brew install openjdk@21)"
"$JAVA_BIN" -version 2>&1 | head -1

# ── Work dir + agent jar (isolated from ~/jenkins-agent used by jenkins-c) ──
mkdir -p "$WORK_DIR/remoting"
# a truncated jar from an interrupted download breaks java silently — validate size
if [[ -f "$WORK_DIR/agent.jar" ]] && [[ "$(stat -f%z "$WORK_DIR/agent.jar" 2>/dev/null || stat -c%s "$WORK_DIR/agent.jar")" -lt 1000000 ]]; then
    log "existing agent.jar is too small to be valid — re-downloading"
    rm -f "$WORK_DIR/agent.jar"
fi
if [[ ! -f "$WORK_DIR/agent.jar" ]]; then
    log "Downloading agent.jar from ${AGENT_URL}"
    curl -sfL --retry 3 --retry-delay 3 \
        "${AGENT_URL%/}/jnlpJars/agent.jar" -o "$WORK_DIR/agent.jar" \
        || err "agent.jar download failed — is jenkins-b reachable?"
fi
[[ "$(stat -f%z "$WORK_DIR/agent.jar" 2>/dev/null || stat -c%s "$WORK_DIR/agent.jar")" -ge 1000000 ]] \
    || err "downloaded agent.jar is corrupt (size check failed)"

# ── Stop previous instance if re-running ────────────────────────────────────
if launchctl print "gui/$(id -u)/$PLIST_LABEL" &>/dev/null; then
    log "Stopping existing $PLIST_LABEL service"
    launchctl bootout "gui/$(id -u)" "$PLIST_PATH" 2>/dev/null || \
        launchctl remove "$PLIST_LABEL" 2>/dev/null || true
    sleep 2
fi

# ── Write launchd plist (user-level, survives reboot with auto-login) ──────
log "Writing plist: $PLIST_PATH"
python3 - "$PLIST_PATH" <<PYEOF
import os, plistlib, sys

path = sys.argv[1]
secret = os.environ["AGENT_SECRET"]
name = os.environ["AGENT_NAME"]
url = os.environ["AGENT_URL"]
work = os.path.expanduser("~/jenkins-agent-b")
java = os.environ.get("JAVA_BIN", "/opt/homebrew/opt/openjdk@21/bin/java")

home = os.path.expanduser("~")
plist = {
    "Label": f"com.jenkins.{name}",
    "ProgramArguments": [
        java, "-jar", f"{work}/agent.jar",
        "-url", url,
        "-secret", secret,
        "-name", name,
        "-webSocket",
        "-workDir", work,
    ],
    "EnvironmentVariables": {
        "PATH": "/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin"
                ":/Users/dk/.rbenv/shims:/Users/dk/.rbenv/bin"
                ":/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "LANG": "en_US.UTF-8",
        "HOME": home,
        "RBENV_ROOT": f"{home}/.rbenv",
    },
    "WorkingDirectory": work,
    "RunAtLoad": True,
    "KeepAlive": True,
    "StandardOutPath": f"{work}/agent.log",
    "StandardErrorPath": f"{work}/agent.log",
}
with open(path, "wb") as f:
    plistlib.dump(plist, f)
print(f"written: {path}")
PYEOF

plutil -lint "$PLIST_PATH" >/dev/null || err "plist validation failed"

# ── Load (user domain — no sudo needed) ─────────────────────────────────────
launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH" 2>/dev/null || \
    launchctl load -w "$PLIST_PATH"

# ── Verify ───────────────────────────────────────────────────────────────────
log "Waiting for connection (max 30s)..."
for _ in $(seq 1 15); do
    if tail -5 "$WORK_DIR/agent.log" 2>/dev/null | grep -q "Connected"; then
        ok "Agent $AGENT_NAME connected to ${AGENT_URL}"
        exit 0
    fi
    sleep 2
done
err "Agent did not connect within 30s — check $WORK_DIR/agent.log"
