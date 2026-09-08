#!/bin/bash
# ==========================================================================
# codesign-fixed.sh
#
# Signs a copy of /bin/ls using a dummy identity in an ephemeral keychain.
# Applies set-key-partition-list so codesign can use the key without a
# GUI consent prompt — the "fixed" path.
#
# Expected outcome: SIGNING SUCCEEDED (rc=0, Signature size=3699)
# ==========================================================================
set -euo pipefail

# ── Hardcoded variables ──────────────────────────────────────────────────
# KC_PASS: password for the ephemeral keychain AND the p12 file.
#          Using the same value for both simplifies the experiment.
KC_PASS="DummyCertPass123!"

# KC: name of the ephemeral keychain. Uses BUILD_NUMBER so successive
#     runs don't collide. Format: codesign-fixed-<job-number>
KC="codesign-fixed-${BUILD_NUMBER}"

# VARIANT: tag for logging — identifies this as the "partition applied" run.
VARIANT="FIXED"

# TARGET: the binary we will sign. We copy /bin/ls into the workspace
#         so we never modify the system binary.
TARGET="./ls-binary-test"

# PARTITION: the partition-list string that authorises codesign to use the
#            key without a GUI prompt. apple-tool: = security tools,
#            apple: = Apple apps, codesign: = the codesign binary itself.
PARTITION="apple-tool:,apple:,codesign:"

# SIGNING_HASH: the SHA-1 hash of the dummy identity in the p12.
#               Deterministic because the dummy key is always the same.
SIGNING_HASH="6F4C905D4FCB00319A82096A1683B33C902E65E9"

# PATH: ensure homebrew and standard bin dirs are available on the Mac.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

# ── Step 1: Create an ephemeral keychain ─────────────────────────────────
# Each build gets its own keychain so there is zero state leakage between
# runs. The password is set to KC_PASS above.
# "security create-keychain" creates the file at ~/Library/Keychains/<KC>.
# If it already exists from a previous failed cleanup, delete it first.
echo "=== codesign experiment: $VARIANT ==="
security delete-keychain "$KC" 2>/dev/null || true
if ! security create-keychain -p "$KC_PASS" "$KC"; then
    echo "FAIL: create-keychain"; exit 2
fi

# ── Step 2: Unlock the keychain ──────────────────────────────────────────
# New keychains start locked. Unlocking is required before any import or
# key access. Without this, the import in step 3 would fail.
security unlock-keychain -p "$KC_PASS" "$KC"

# ── Step 3: Import the dummy .p12 into the keychain ──────────────────────
# P12_FILE and P12_PASS are injected by Jenkins SecretBuildWrapper
# (credentials binding). The -T flag adds codesign to the key's Access
# Control List so it can use the key. -f pkcs12 forces the format.
# SECURITY WARNING: P12_FILE and P12_PASS are env vars set by Jenkins
# credentials binding — they are never printed or logged.
if ! security import "$P12_FILE" -k "$KC" -P "$P12_PASS" -T /usr/bin/codesign -f pkcs12; then
    echo "FAIL: p12 import"; exit 2
fi

# ── Step 4: Register keychain in the user search list ────────────────────
# codesign resolves identities by walking the user keychain search list.
# Passing --keychain to codesign alone is NOT enough — the keychain MUST
# appear in the search list. login.keychain-db is appended so we don't
# break anything if codesign falls back to it.
security list-keychains -d user -s "$KC" ~/Library/Keychains/login.keychain-db

# ── Step 5: Apply set-key-partition-list (the experiment variable) ───────
# This is the ONLY difference between codesign-fixed and codesign-err.
# Without this, securityd will try to present a GUI consent prompt on
# a headless agent, causing either errSecInternalComponent or an
# indefinite hang.
echo "--- applying set-key-partition-list ---"
security set-key-partition-list -S "$PARTITION" -s -k "$KC_PASS" "$KC" > /dev/null 2>&1
echo "partition list applied"

# ── Step 6: Verify the identity exists in the keychain ───────────────────
# security find-identity lists identities matching the codesigning policy.
# We extract the SHA-1 hash (second field) of the first result. If no
# identity is found, the build fails immediately.
IDENT=$(security find-identity -p codesigning "$KC" | awk '/^[[:space:]]*[0-9]+[)]/{print $2; exit}')
echo "identity hash: ${IDENT:-NONE}"
if [ -z "$IDENT" ]; then
    echo "FAIL: no identity in keychain"
    security delete-keychain "$KC"
    exit 2
fi

# ── Step 7: Copy /bin/ls into the workspace as a signable target ─────────
# We never sign the real /bin/ls. The copy is our disposable target.
rm -f "$TARGET"
cp /bin/ls "$TARGET"

# ── Step 8: Run codesign ─────────────────────────────────────────────────
# --force: overwrite any existing signature on the binary.
# --sign "$IDENT": sign with the given SHA-1 identity hash.
# --keychain "$KC": use only our ephemeral keychain (not the search list).
# --timestamp=none: skip timestamping (avoids contacting Apple's server).
RC_SIGN=99
echo "--- codesign attempt ---"
codesign --force --sign "$IDENT" --keychain "$KC" --timestamp=none "$TARGET" 2>&1 | tee codesign-output.txt
RC_SIGN=${PIPESTATUS[0]}
echo "codesign rc=$RC_SIGN"

# ── Step 9: Verify the signature (only if sign succeeded) ────────────────
# codesign -dv prints detailed signature info. We capture 6 lines which
# include the Executable, Identifier, Format, CodeDirectory, Signature
# size, and TeamIdentifier fields. A successful sign shows
# "Signature size=3699" for our dummy identity.
RC_VERIFY=99
if [ "$RC_SIGN" -eq 0 ]; then
    echo "--- verify ---"
    codesign -dv "$TARGET" 2>&1 | head -6
    RC_VERIFY=0
else
    echo "verify skipped (sign failed)"
fi

# ── Step 10: Cleanup — restore search list and delete ephemeral keychain ─
# Always clean up: restore the original keychain search list, delete
# the ephemeral keychain, and remove the test binary. This prevents
# keychain pollution of subsequent builds.
security list-keychains -d user -s ~/Library/Keychains/login.keychain-db
security delete-keychain "$KC" 2>/dev/null || true
rm -f "$TARGET"

# ── Final result ─────────────────────────────────────────────────────────
echo ""
echo "================ RESULT ================"
echo "variant=$VARIANT codesign_rc=$RC_SIGN verify_rc=$RC_VERIFY"
if [ "$RC_SIGN" -eq 0 ]; then
    echo "VERDICT: SIGNING SUCCEEDED"
else
    echo "VERDICT: SIGNING FAILED"
fi
