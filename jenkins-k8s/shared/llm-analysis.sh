#!/bin/bash
# LLM failure analysis: true A→B failover — produces ONE clean analysis output
# Env vars consumed:
#   FAILED_STAGE      — stage name where the build broke
#   ERROR_SNIPPET     — grep-extracted error lines
#   LOG_TAIL          — last N lines of the console log
#   BUILD_NUMBER      — Jenkins build number
#   JOB_NAME          — Jenkins job name
#   BUILD_TYPE        — e.g. "iOS-Enterprise", "Android-APK" (optional)
#   APP_NAME          — application name (optional)
#   ENVIRONMENT       — target env e.g. Sit/Uat/Prod (optional)
#   EXTRA_CONTEXT     — any additional context for the LLM (optional)
#   LLM_ENDPOINT_A    — primary Ollama endpoint
#   LLM_ENDPOINT_B    — secondary Ollama endpoint
#   LLM_TIMEOUT       — curl max-time in seconds (default 120)
#   LLM_MODEL_PRIORITY — space-separated model priority list
set -euo pipefail

FAILED_STAGE="${FAILED_STAGE:-Unknown}"
ERROR_SNIPPET="${ERROR_SNIPPET:-No error snippet provided}"
LOG_TAIL="${LOG_TAIL:-No log available}"
BUILD_NUMBER="${BUILD_NUMBER:-0}"
JOB_NAME="${JOB_NAME:-unknown}"
BUILD_TYPE="${BUILD_TYPE:-}"
APP_NAME="${APP_NAME:-}"
ENVIRONMENT="${ENVIRONMENT:-}"
EXTRA_CONTEXT="${EXTRA_CONTEXT:-}"
TIMESTAMP=$(TZ=Asia/Kolkata date +"%Y-%m-%d %H:%M:%S IST")

ENDPOINT_A="${LLM_ENDPOINT_A:-http://100.89.50.27:11434}"
ENDPOINT_B="${LLM_ENDPOINT_B:-http://100.104.14.62:21434}"
TIMEOUT="${LLM_TIMEOUT:-120}"

# Priority order for model selection (most capable first)
MODEL_PRIORITY="${LLM_MODEL_PRIORITY:-qwen2.5-coder qwen2.5 deepseek-coder codellama llama3 llama2 mistral phi}"

# Build optional context header
CONTEXT_HEADER=""
if [ -n "${BUILD_TYPE}" ]; then
    CONTEXT_HEADER="${CONTEXT_HEADER}Build Type: ${BUILD_TYPE}\n"
fi
if [ -n "${APP_NAME}" ]; then
    CONTEXT_HEADER="${CONTEXT_HEADER}Application: ${APP_NAME}\n"
fi
if [ -n "${ENVIRONMENT}" ]; then
    CONTEXT_HEADER="${CONTEXT_HEADER}Target Environment: ${ENVIRONMENT}\n"
fi
if [ -n "${EXTRA_CONTEXT}" ]; then
    CONTEXT_HEADER="${CONTEXT_HEADER}\nAdditional Context:\n${EXTRA_CONTEXT}\n"
fi

PROMPT="You are a senior CI/CD build failure analyst specialising in mobile (iOS/Android) builds on Jenkins.
Analyse this build failure and give actionable output.

$(printf '%b' "${CONTEXT_HEADER}")
Job: ${JOB_NAME}
Build #: ${BUILD_NUMBER}
Failed Stage: ${FAILED_STAGE}
Timestamp: ${TIMESTAMP}

Error Snippet (grep-extracted error lines):
\`\`\`
${ERROR_SNIPPET}
\`\`\`

Console Log Tail (last 200 lines):
\`\`\`
${LOG_TAIL}
\`\`\`

Respond in Markdown with exactly these sections:

## Root Cause
1–2 sentences identifying what broke and why.

## Likely Fix
Numbered steps to resolve the issue. Include code blocks / commands where relevant.

## Preventive Measure
One suggestion to stop this class of failure recurring in CI.

## Confidence
High / Medium / Low with one sentence of reasoning.

## Failure Flow
A Mermaid flowchart showing which step broke and what triggered it.
\`\`\`mermaid
flowchart TD
    A[Build triggered] --> B[${FAILED_STAGE}]
    B --> C{Failure}
    ...
\`\`\`

## References
Relevant documentation links, error codes, or SDK notes."

IMPROVEMENT_PROMPT="Based on this Jenkins build failure, suggest 2-3 concrete improvements to the pipeline/build configuration to prevent recurrence.

Job: ${JOB_NAME}
Build #: ${BUILD_NUMBER}
Failed Stage: ${FAILED_STAGE}

Error Snippet:
\`\`\`
${ERROR_SNIPPET}
\`\`\`

Respond with a numbered list of 2-3 actionable improvements. Be specific and concise."

# ---------------------------------------------------------------------------
# Returns the best available model name from an Ollama endpoint,
# or empty string if endpoint is unreachable / has no models.
# ---------------------------------------------------------------------------
best_model() {
    local endpoint="$1"
    local available
    available=$(curl -sf --max-time 5 "${endpoint}/api/tags" \
        | jq -r '.models[].name' 2>/dev/null) || return 0
    for priority in ${MODEL_PRIORITY}; do
        local match
        match=$(echo "$available" | grep -i "^${priority}" | head -1)
        if [ -n "$match" ]; then
            echo "$match"
            return
        fi
    done
    # Fall back to whatever is listed first
    echo "$available" | head -1
}

# ---------------------------------------------------------------------------
# Calls an Ollama endpoint and writes the raw response to output_file.
# The function itself does not fail — errors are written to output_file.
# ---------------------------------------------------------------------------
call_ollama() {
    local endpoint="$1"
    local model="$2"
    local output_file="$3"
    local prompt_var="$4"   # name of the variable holding the prompt text
    echo "  → Calling ${endpoint} model: ${model}" >&2
    local payload
    payload=$(jq -n \
        --arg model "$model" \
        --arg prompt "${!prompt_var}" \
        '{model: $model, prompt: $prompt, stream: false}')
    curl -sf --max-time "$TIMEOUT" \
        -X POST "${endpoint}/api/generate" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        | jq -r '.response // ""' \
        > "$output_file" 2>/dev/null \
        || true
}

# ---------------------------------------------------------------------------
# try_endpoint <endpoint> <model> <output_file> <prompt_var>
# Calls the endpoint and validates the response (non-empty, length > 50).
# Returns 0 on success, 1 on failure.
# ---------------------------------------------------------------------------
try_endpoint() {
    local endpoint="$1"
    local model="$2"
    local output_file="$3"
    local prompt_var="$4"
    call_ollama "$endpoint" "$model" "$output_file" "$prompt_var"
    local response
    response=$(cat "$output_file" 2>/dev/null || true)
    local len="${#response}"
    if [ -n "$response" ] && [ "$len" -gt 50 ]; then
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------------
# Main: failover logic
# ---------------------------------------------------------------------------
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "[llm-analysis] Starting analysis for: ${JOB_NAME} #${BUILD_NUMBER} — ${FAILED_STAGE}" >&2

WINNING_ENDPOINT=""
WINNING_MODEL=""
ANALYSIS_TEXT=""

# --- Try endpoint A ---
echo "[1/2] Checking endpoint A: ${ENDPOINT_A}" >&2
MODEL_A=$(best_model "$ENDPOINT_A")
if [ -n "$MODEL_A" ]; then
    echo "  Selected model: ${MODEL_A}" >&2
    if try_endpoint "$ENDPOINT_A" "$MODEL_A" "${TMP}/analysis.md" "PROMPT"; then
        WINNING_ENDPOINT="$ENDPOINT_A"
        WINNING_MODEL="$MODEL_A"
        ANALYSIS_TEXT=$(cat "${TMP}/analysis.md")
        echo "  Endpoint A succeeded." >&2
    else
        echo "  Endpoint A returned empty or too-short response." >&2
    fi
else
    echo "  Endpoint A unreachable or no models found." >&2
fi

# --- Try endpoint B if A failed ---
if [ -z "$WINNING_ENDPOINT" ]; then
    echo "[2/2] Falling back to endpoint B: ${ENDPOINT_B}" >&2
    MODEL_B=$(best_model "$ENDPOINT_B")
    if [ -n "$MODEL_B" ]; then
        echo "  Selected model: ${MODEL_B}" >&2
        if try_endpoint "$ENDPOINT_B" "$MODEL_B" "${TMP}/analysis.md" "PROMPT"; then
            WINNING_ENDPOINT="$ENDPOINT_B"
            WINNING_MODEL="$MODEL_B"
            ANALYSIS_TEXT=$(cat "${TMP}/analysis.md")
            echo "  Endpoint B succeeded." >&2
        else
            echo "  Endpoint B returned empty or too-short response." >&2
        fi
    else
        echo "  Endpoint B unreachable or no models found." >&2
    fi
else
    echo "[2/2] Skipping endpoint B (endpoint A succeeded)." >&2
fi

# Determine Analysis Source label
if [ -n "$WINNING_ENDPOINT" ]; then
    ANALYSIS_SOURCE="${WINNING_MODEL} via ${WINNING_ENDPOINT}"
else
    ANALYSIS_SOURCE="Both endpoints unreachable"
    ANALYSIS_TEXT="Both LLM endpoints were unreachable or returned insufficient responses. Manual investigation is required."
fi

# ---------------------------------------------------------------------------
# Improvement suggestions — same A→B failover, using the winning endpoint
# ---------------------------------------------------------------------------
echo "[llm-analysis] Requesting improvement suggestions (best-effort)…" >&2
IMPROVEMENT_TEXT=""

if [ -n "$WINNING_ENDPOINT" ]; then
    # Try the winning endpoint first for improvements
    if try_endpoint "$WINNING_ENDPOINT" "$WINNING_MODEL" "${TMP}/improvements.md" "IMPROVEMENT_PROMPT"; then
        IMPROVEMENT_TEXT=$(cat "${TMP}/improvements.md")
        echo "  Improvement suggestions obtained from ${WINNING_ENDPOINT}." >&2
    else
        echo "  Winning endpoint returned empty improvement suggestions, trying other endpoint…" >&2
        # Try the other endpoint
        if [ "$WINNING_ENDPOINT" = "$ENDPOINT_A" ]; then
            ALT_MODEL=$(best_model "$ENDPOINT_B")
            ALT_ENDPOINT="$ENDPOINT_B"
        else
            ALT_MODEL=$(best_model "$ENDPOINT_A")
            ALT_ENDPOINT="$ENDPOINT_A"
        fi
        if [ -n "$ALT_MODEL" ]; then
            if try_endpoint "$ALT_ENDPOINT" "$ALT_MODEL" "${TMP}/improvements.md" "IMPROVEMENT_PROMPT"; then
                IMPROVEMENT_TEXT=$(cat "${TMP}/improvements.md")
                echo "  Improvement suggestions obtained from fallback ${ALT_ENDPOINT}." >&2
            fi
        fi
    fi
fi

if [ -z "$IMPROVEMENT_TEXT" ]; then
    IMPROVEMENT_TEXT="Not available"
    echo "[llm-analysis] No improvement suggestions obtained." >&2
fi

# ---------------------------------------------------------------------------
# Write llm-analysis.md — single clean output
# ---------------------------------------------------------------------------
{
    printf '# Jenkins Build Failure Analysis\n\n'
    printf '## Build Metadata\n\n'
    printf '| Field | Value |\n'
    printf '|---|---|\n'
    printf '| **Job** | %s |\n'           "${JOB_NAME}"
    printf '| **Build #** | %s |\n'       "${BUILD_NUMBER}"
    printf '| **Failed Stage** | %s |\n'  "${FAILED_STAGE}"
    printf '| **Timestamp** | %s |\n'     "${TIMESTAMP}"
    printf '| **Analysis Source** | %s |\n' "${ANALYSIS_SOURCE}"
    printf '\n> Mermaid diagrams render natively in GitHub, GitLab, and VS Code.\n\n'
    printf -- '---\n\n'
    printf '## Failure Analysis\n\n'
    printf '%s\n' "${ANALYSIS_TEXT}"
    printf '\n\n---\n\n'
    printf '## Improvement Suggestions\n\n'
    printf '%s\n' "${IMPROVEMENT_TEXT}"
} > llm-analysis.md

echo "[llm-analysis] Done — written to llm-analysis.md" >&2
exit 0
