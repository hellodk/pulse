#!/bin/bash
# validate_profiles.sh — Verify that profiles from profile_dir are correctly installed.
#
# Usage:
#   validate_profiles.sh <profile_dir> <install_dir> <output_json>
#
# Writes a JSON array of { uuid, name, status, reason } to output_json.
# Exit code 1 if any profile fails validation.
set -euo pipefail

PROFILE_DIR="$1"
INSTALL_DIR="$2"
OUTPUT_JSON="$3"

PASS=0
FAIL=0

echo "[]" > "$OUTPUT_JSON"   # valid JSON in case of early exit

TMP_RESULTS=$(mktemp /tmp/validation_results.XXXXXX.json)
echo "[" > "$TMP_RESULTS"
FIRST=true

for profile in "$PROFILE_DIR"/*.mobileprovision; do
    [ -f "$profile" ] || continue

    TMP=$(mktemp /tmp/pp.XXXXXX.plist)
    if ! security cms -D -i "$profile" > "$TMP" 2>/dev/null; then
        echo "[ERROR] Cannot decode: $(basename "$profile")"
        rm -f "$TMP"
        continue
    fi

    UUID=$(/usr/libexec/PlistBuddy -c "Print :UUID" "$TMP" 2>/dev/null || echo "")
    NAME=$(/usr/libexec/PlistBuddy -c "Print :Name" "$TMP" 2>/dev/null || echo "Unknown")
    rm -f "$TMP"

    TARGET="$INSTALL_DIR/$UUID.mobileprovision"
    STATUS="FAIL"
    REASON=""

    if [ ! -f "$TARGET" ]; then
        REASON="not found at install path"
    else
        # Re-decode installed copy and verify UUID matches
        TMP2=$(mktemp /tmp/pp_installed.XXXXXX.plist)
        if ! security cms -D -i "$TARGET" > "$TMP2" 2>/dev/null; then
            REASON="installed file is unreadable"
        else
            INSTALLED_UUID=$(/usr/libexec/PlistBuddy -c "Print :UUID" "$TMP2" 2>/dev/null || echo "")
            if [ "$INSTALLED_UUID" = "$UUID" ]; then
                STATUS="PASS"
                ((PASS++)) || true
            else
                REASON="UUID mismatch: expected $UUID got $INSTALLED_UUID"
            fi
        fi
        rm -f "$TMP2"
    fi

    [ "$STATUS" = "PASS" ] || ((FAIL++)) || true

    STATUS_ICON="✅"
    [ "$STATUS" = "PASS" ] || STATUS_ICON="❌"
    echo "[$STATUS_ICON $STATUS] $NAME ($UUID)${REASON:+  — $REASON}"

    # Append to JSON
    $FIRST || echo "," >> "$TMP_RESULTS"
    FIRST=false
    cat >> "$TMP_RESULTS" <<JSONROW
  {
    "uuid": "$UUID",
    "name": "$NAME",
    "status": "$STATUS",
    "reason": "$REASON"
  }
JSONROW
done

echo "]" >> "$TMP_RESULTS"
mv "$TMP_RESULTS" "$OUTPUT_JSON"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Passed : $PASS"
echo "  Failed : $FAIL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

[ "$FAIL" -eq 0 ] || exit 1
