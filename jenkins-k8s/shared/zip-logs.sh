#!/bin/bash
# zip-logs.sh — Collect relevant build artefacts into a ZIP for archiving.
# Best-effort: always exits 0.
#
# Env vars consumed:
#   BUILD_NUMBER — Jenkins build number (defaults to 0)
#
# Files collected (maxdepth 4, exclusions applied):
#   *.log  *.xml  *.json  build/*.log  reports/*
#   build-error-report.txt  llm-analysis.md
#
# Exclusions: node_modules/  .git/  vendor/  Pods/  DerivedData/

set -uo pipefail

BUILD_NUMBER="${BUILD_NUMBER:-0}"
ZIP_NAME="build-logs-${BUILD_NUMBER}.zip"
TMP_LIST=$(mktemp)

trap 'rm -f "$TMP_LIST"' EXIT

echo "[zip-logs] Collecting log files for build #${BUILD_NUMBER}…" >&2

# Prune directories we never want to descend into
PRUNE_EXPR=(
    -path './node_modules' -prune
    -o -path './.git'       -prune
    -o -path './vendor'     -prune
    -o -path './Pods'       -prune
    -o -path './DerivedData' -prune
)

# Patterns to include
INCLUDE_EXPR=(
    -o \( -maxdepth 4 -name '*.log'                    -print \)
    -o \( -maxdepth 4 -name '*.xml'                    -print \)
    -o \( -maxdepth 4 -name '*.json'                   -print \)
    -o \( -maxdepth 4 -name 'build-error-report.txt'   -print \)
    -o \( -maxdepth 4 -name 'llm-analysis.md'          -print \)
    -o \( -maxdepth 4 -path './reports/*'              -print \)
)

# Run find; -maxdepth in each leaf clause limits depth relative to .
find . \
    "${PRUNE_EXPR[@]}" \
    "${INCLUDE_EXPR[@]}" \
    2>/dev/null \
    | sort -u \
    > "$TMP_LIST" || true

FILE_COUNT=$(wc -l < "$TMP_LIST" | tr -d ' ')

if [ "$FILE_COUNT" -eq 0 ]; then
    echo "[zip-logs] No matching files found. Creating ZIP with placeholder README." >&2
    local_readme=$(mktemp --suffix=.md)
    printf '# No log files found\n\nBuild #%s produced no collectable artefacts.\n' \
        "${BUILD_NUMBER}" > "$local_readme"
    zip -q "$ZIP_NAME" "$local_readme" 2>/dev/null || true
    rm -f "$local_readme"
else
    echo "[zip-logs] Found ${FILE_COUNT} file(s). Zipping…" >&2
    # Feed file list to zip via stdin (-@)
    zip -q "$ZIP_NAME" -@ < "$TMP_LIST" 2>/dev/null || true
fi

if [ -f "$ZIP_NAME" ]; then
    ZIP_SIZE=$(du -sh "$ZIP_NAME" 2>/dev/null | cut -f1)
    echo "[zip-logs] Created: ${ZIP_NAME} | files: ${FILE_COUNT} | size: ${ZIP_SIZE}" >&2
else
    echo "[zip-logs] WARNING: ZIP was not created. Skipping." >&2
fi

exit 0
