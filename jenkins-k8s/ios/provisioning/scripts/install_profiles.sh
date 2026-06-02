#!/bin/bash
# install_profiles.sh — Install .mobileprovision files into Xcode's profile store.
#
# Usage:
#   install_profiles.sh <profile_dir> <install_dir> <backup_dir> <replace_existing>
#
# Exit codes:
#   0  all profiles installed (or legitimately skipped)
#   1  at least one profile could not be installed
set -euo pipefail

PROFILE_DIR="$1"
INSTALL_DIR="$2"
BACKUP_DIR="$3"
REPLACE_EXISTING="${4:-false}"

mkdir -p "$INSTALL_DIR" "$BACKUP_DIR"

INSTALLED=0
REPLACED=0
SKIPPED=0
ERRORS=0

for profile in "$PROFILE_DIR"/*.mobileprovision; do
    [ -f "$profile" ] || continue

    TMP=$(mktemp /tmp/pp.XXXXXX.plist)
    if ! security cms -D -i "$profile" > "$TMP" 2>/dev/null; then
        echo "[ERROR] Cannot decode: $(basename "$profile")"
        rm -f "$TMP"
        ((ERRORS++)) || true
        continue
    fi

    UUID=$(/usr/libexec/PlistBuddy -c "Print :UUID" "$TMP" 2>/dev/null || echo "")
    NAME=$(/usr/libexec/PlistBuddy -c "Print :Name" "$TMP" 2>/dev/null || echo "Unknown")
    rm -f "$TMP"

    if [ -z "$UUID" ]; then
        echo "[ERROR] Could not extract UUID from: $(basename "$profile")"
        ((ERRORS++)) || true
        continue
    fi

    TARGET="$INSTALL_DIR/$UUID.mobileprovision"

    if [ -f "$TARGET" ]; then
        # Always backup the existing file before touching it
        cp "$TARGET" "$BACKUP_DIR/$UUID.mobileprovision.bak"

        if [ "$REPLACE_EXISTING" != "true" ]; then
            echo "[SKIP]    $NAME  ($UUID)  — already installed"
            ((SKIPPED++)) || true
            continue
        fi

        echo "[REPLACE] $NAME  ($UUID)"
        ((REPLACED++)) || true
    else
        echo "[INSTALL] $NAME  ($UUID)"
        ((INSTALLED++)) || true
    fi

    cp "$profile" "$TARGET"
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Installed : $INSTALLED"
echo "  Replaced  : $REPLACED"
echo "  Skipped   : $SKIPPED"
echo "  Errors    : $ERRORS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

[ "$ERRORS" -eq 0 ] || exit 1
