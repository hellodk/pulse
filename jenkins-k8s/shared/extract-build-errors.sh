#!/usr/bin/env bash
# =============================================================================
# extract-build-errors.sh
#
# PURPOSE
#   Distil a verbose iOS/Android build log (100k+ lines from xcodebuild)
#   into ≤ MAX_LINES lines of actionable errors suitable for LLM analysis.
#
# THE PROBLEM
#   xcodebuild with xcpretty still produces 100,000+ raw lines.  Sending
#   even the last 200 lines to an LLM is unreliable — the actual error may
#   be anywhere in the log, and raw compile output is mostly noise.
#
# THE SOLUTION
#   1. Prefer xcpretty-filtered output (xcodebuild-errors.log) when available.
#      xcpretty already strips compile progress lines and keeps only
#      errors, warnings, and test results.  Typical size: 50–500 lines.
#
#   2. Fall back to targeted grep-based extraction from the raw log:
#      a. Build failure summary (lines around BUILD FAILED)
#      b. Compiler / linker errors with filename:line context
#      c. Code signing errors (errSec*, keychain, provisioning profile)
#      d. CocoaPods errors ([!] prefix, Podfile, LoadError)
#      e. Node / Metro / pnpm errors
#      f. Test failures
#      Each section is capped; total capped at MAX_LINES.
#
# INPUTS (checked in priority order)
#   $WORKSPACE/build/xcodebuild-errors.log   xcpretty-filtered output (preferred)
#   $WORKSPACE/build/xcodebuild-raw.log      raw xcodebuild output (fallback)
#   $WORKSPACE/build/pod-install.log         CocoaPods output
#   $WORKSPACE/build/node-install.log        pnpm / npm output
#   Stdin                                    piped content (last resort)
#
# OUTPUT
#   stdout — concise error report, ≤ MAX_LINES lines, ready for LLM prompt
#
# USAGE
#   bash extract-build-errors.sh                      # auto-detect files
#   bash extract-build-errors.sh /path/to/log.txt     # explicit input
#   cat some.log | MAX_LINES=100 bash extract-build-errors.sh -
#
# ENVIRONMENT
#   WORKSPACE    Jenkins workspace path (default: current directory)
#   MAX_LINES    Maximum output lines (default: 200)
#   MAX_LINE_LEN Maximum characters per line (default: 300, avoids giant stack traces)
# =============================================================================
set -euo pipefail

WORKSPACE="${WORKSPACE:-$(pwd)}"
MAX_LINES="${MAX_LINES:-200}"
MAX_LINE_LEN="${MAX_LINE_LEN:-300}"
EXPLICIT_INPUT="${1:-}"

PRETTY_LOG="${WORKSPACE}/build/xcodebuild-errors.log"
RAW_LOG="${WORKSPACE}/build/xcodebuild-raw.log"
POD_LOG="${WORKSPACE}/build/pod-install.log"
NODE_LOG="${WORKSPACE}/build/node-install.log"

# ── Helpers ───────────────────────────────────────────────────────────────────
has_file() { [[ -s "$1" ]]; }

# Truncate lines longer than MAX_LINE_LEN to avoid sending huge stack frames
truncate_lines() {
    awk -v max="${MAX_LINE_LEN}" '{ print (length($0) > max) ? substr($0,1,max) "…" : $0 }'
}

# Extract N lines of context around a matching pattern in a file
context_grep() {
    local pattern="$1" file="$2" before="${3:-2}" after="${4:-2}" maxout="${5:-30}"
    grep -n "${pattern}" "${file}" 2>/dev/null \
        | head -20 \
        | cut -d: -f1 \
        | while IFS= read -r linenum; do
            local start=$(( linenum > before ? linenum - before : 1 ))
            local end=$(( linenum + after ))
            sed -n "${start},${end}p" "${file}"
            echo "---"
          done \
        | head -"${maxout}"
}

# ── Strategy 1: xcpretty-filtered output ─────────────────────────────────────
# xcpretty strips all compile progress noise; its output is already a concise
# error summary.  If it fits in MAX_LINES, use it directly.
if [[ -z "${EXPLICIT_INPUT}" ]] && has_file "${PRETTY_LOG}"; then
    TOTAL_LINES=$(wc -l < "${PRETTY_LOG}" | tr -d ' ')
    if [[ "${TOTAL_LINES}" -le "${MAX_LINES}" ]]; then
        echo "=== xcpretty filtered output (${TOTAL_LINES} lines) ==="
        truncate_lines < "${PRETTY_LOG}"
        exit 0
    fi
    # xcpretty output is large — it has many warnings; extract just the error sections
    echo "=== xcpretty errors only (from ${TOTAL_LINES}-line xcpretty log) ==="
    grep -E "error:|BUILD FAILED|\bfailed\b|✗" "${PRETTY_LOG}" 2>/dev/null \
        | grep -iv "^\s*#\|noterror\|^Binary\|^\s*//" \
        | truncate_lines \
        | head -"${MAX_LINES}"
    exit 0
fi

# ── Strategy 2: explicit input or raw log ────────────────────────────────────
if [[ "${EXPLICIT_INPUT}" == "-" ]]; then
    INPUT_FILE="$(mktemp)"
    trap "rm -f '${INPUT_FILE}'" EXIT
    cat > "${INPUT_FILE}"
elif [[ -n "${EXPLICIT_INPUT}" ]] && [[ -f "${EXPLICIT_INPUT}" ]]; then
    INPUT_FILE="${EXPLICIT_INPUT}"
elif has_file "${RAW_LOG}"; then
    INPUT_FILE="${RAW_LOG}"
else
    echo "No build log found. Checked:"
    echo "  ${PRETTY_LOG}"
    echo "  ${RAW_LOG}"
    echo "  explicit arg: ${EXPLICIT_INPUT:-none}"
    exit 0
fi

TOTAL_LINES=$(wc -l < "${INPUT_FILE}" | tr -d ' ')
echo "=== Smart extraction from ${TOTAL_LINES}-line build log ==="
echo ""

# Section helper: prints header and content, skips if empty
section() {
    local header="$1"
    shift
    local content
    content=$("$@" 2>/dev/null || true)
    if [[ -n "${content}" ]]; then
        echo "─── ${header} ───────────────────────────────────────────"
        echo "${content}"
        echo ""
    fi
}

{
    # ── 1. Build failure summary ─────────────────────────────────────────────
    # Find the LAST "BUILD FAILED" marker and capture the 40 lines before it.
    # This is where xcodebuild prints the final error summary.
    section "Build Failure Summary" bash -c "
        FAIL_LINE=\$(grep -n 'BUILD FAILED\|\*\* BUILD FAILED \*\*\|The following build commands failed' \
            '${INPUT_FILE}' | tail -1 | cut -d: -f1)
        if [[ -n \"\${FAIL_LINE}\" ]]; then
            START=\$(( FAIL_LINE > 50 ? FAIL_LINE - 50 : 1 ))
            sed -n \"\${START},\${FAIL_LINE}p\" '${INPUT_FILE}'
        fi
    "

    # ── 2. Compiler / linker errors ──────────────────────────────────────────
    # Format: /path/to/File.swift:12:34: error: use of undeclared identifier 'X'
    section "Compiler and Linker Errors" bash -c "
        grep -nE '^.+\.[a-zA-Z]+:[0-9]+:[0-9]*:? error:' '${INPUT_FILE}' \
            | grep -v 'note:' \
            | head -25
    "

    # ── 3. Generic error: lines not caught above ─────────────────────────────
    section "Other Error Lines" bash -c "
        grep -iE '^\s*(error|Error):|xcodebuild: error:|ld: error:' '${INPUT_FILE}' \
            | grep -v '^Binary\|noterror\|#' \
            | head -20
    "

    # ── 4. Code signing errors ───────────────────────────────────────────────
    section "Code Signing Errors" bash -c "
        grep -iE \
            'errSec[A-Za-z]+|Code Sign error|codesign.*failed|codesign.*error|
             provisioning profile.*not found|provisioning profile.*expired|
             certificate.*not found|certificate.*expired|
             keychain.*locked|keychain.*not found|
             set-key-partition|errSecInternalComponent|errSecInteractionNotAllowed|
             No signing certificate|iPhone Distribution.*not installed' \
            '${INPUT_FILE}' | head -20
    "

    # ── 5. CocoaPods errors ──────────────────────────────────────────────────
    section "CocoaPods Errors" bash -c "
        grep -E '^\[!\]|^ERROR:.*pod|^Error.*Podfile|LoadError:|undefined method|
                 Unable to find a specification|None of your spec sources contain|
                 pod install.*failed' \
            '${INPUT_FILE}' | head -15
    "

    # ── 6. Node / Metro / pnpm errors ────────────────────────────────────────
    section "Node / Metro / pnpm Errors" bash -c "
        grep -iE 'Metro bundler|Cannot find module|Module not found|
                  pnpm ERR!|npm ERR!|ENOENT|EACCES|
                  SyntaxError.*node_modules|
                  Unable to resolve.*from|
                  Watchman error' \
            '${INPUT_FILE}' | head -15
    "

    # ── 7. Test failures ─────────────────────────────────────────────────────
    section "Test Failures" bash -c "
        grep -E 'Test.*FAILED|FAIL.*Test|✗.*Test|XCTAssert.*failed|
                 failed: caught error|Test Suite.*failed' \
            '${INPUT_FILE}' | head -10
    "

    # ── 8. Last 15 lines of the log ──────────────────────────────────────────
    # Sometimes the final lines contain the most actionable message
    echo "─── Last 15 lines of build output ──────────────────────────────────"
    tail -15 "${INPUT_FILE}"

} | truncate_lines | head -"${MAX_LINES}"

# ── Supplement with pod/node logs if they exist ───────────────────────────────
if has_file "${POD_LOG}"; then
    echo ""
    echo "─── CocoaPods log (last 30 lines of pod-install.log) ────────────────"
    tail -30 "${POD_LOG}" | truncate_lines
fi

if has_file "${NODE_LOG}"; then
    echo ""
    echo "─── Node install log (last 20 lines of node-install.log) ───────────"
    tail -20 "${NODE_LOG}" | truncate_lines
fi
