#!/bin/bash
# LLM failure analysis: queries both Ollama endpoints, picks best available models
# Env vars consumed:
#   FAILED_STAGE    — stage name where the build broke
#   ERROR_SNIPPET   — grep-extracted error lines
#   LOG_TAIL        — last N lines of the console log
#   BUILD_NUMBER    — Jenkins build number
#   JOB_NAME        — Jenkins job name
#   BUILD_TYPE      — e.g. "iOS-Enterprise", "Android-APK" (optional)
#   APP_NAME        — application name (optional)
#   ENVIRONMENT     — target env e.g. Sit/Uat/Prod (optional)
#   EXTRA_CONTEXT   — any additional context for the LLM (optional)
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
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

ENDPOINT_A="http://100.89.50.27:11434"
ENDPOINT_B="http://100.104.14.62:21434"
TIMEOUT=120

# Priority order for model selection (most capable first)
MODEL_PRIORITY="qwen2.5-coder qwen2.5 deepseek-coder codellama llama3 llama2 mistral phi"

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

# Returns the best available model name from an Ollama endpoint,
# or empty string if endpoint is unreachable.
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
    # Fall back to whatever is first
    echo "$available" | head -1
}

call_ollama() {
    local endpoint="$1"
    local model="$2"
    local output_file="$3"
    echo "  → Calling ${endpoint} model: ${model}" >&2
    local payload
    payload=$(jq -n \
        --arg model "$model" \
        --arg prompt "$PROMPT" \
        '{model: $model, prompt: $prompt, stream: false}')
    curl -sf --max-time "$TIMEOUT" \
        -X POST "${endpoint}/api/generate" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        | jq -r '.response // "ERROR: empty response"' \
        > "$output_file" 2>/dev/null \
        || echo "ERROR: call to ${endpoint} failed (timeout or connection refused)" > "$output_file"
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "[llm-analysis] Starting analysis for: ${JOB_NAME} #${BUILD_NUMBER} — ${FAILED_STAGE}" >&2

echo "[1/2] Querying endpoint A: ${ENDPOINT_A}" >&2
MODEL_A=$(best_model "$ENDPOINT_A")
if [ -n "$MODEL_A" ]; then
    echo "  Selected model: ${MODEL_A}" >&2
    call_ollama "$ENDPOINT_A" "$MODEL_A" "${TMP}/endpoint_a.md"
else
    echo "  Endpoint A unreachable or no models found" >&2
    echo "*Endpoint A (${ENDPOINT_A}) unreachable or has no models.*" > "${TMP}/endpoint_a.md"
fi

echo "[2/2] Querying endpoint B: ${ENDPOINT_B}" >&2
MODEL_B=$(best_model "$ENDPOINT_B")
if [ -n "$MODEL_B" ]; then
    echo "  Selected model: ${MODEL_B}" >&2
    call_ollama "$ENDPOINT_B" "$MODEL_B" "${TMP}/endpoint_b.md"
else
    echo "  Endpoint B unreachable or no models found" >&2
    echo "*Endpoint B (${ENDPOINT_B}) unreachable or has no models.*" > "${TMP}/endpoint_b.md"
fi

{
  printf '# Jenkins Build Failure Analysis\n\n'
  printf '## Build Metadata\n\n'
  printf '| Field | Value |\n'
  printf '|---|---|\n'
  printf '| **Job** | %s |\n' "${JOB_NAME}"
  printf '| **Build #** | %s |\n' "${BUILD_NUMBER}"
  printf '| **Failed Stage** | %s |\n' "${FAILED_STAGE}"
  printf '| **Timestamp** | %s |\n' "${TIMESTAMP}"
  printf '\n> Mermaid diagrams render natively in GitHub, GitLab, and VS Code.\n\n'
  printf -- '---\n\n'

  if [ -n "$MODEL_A" ]; then
    printf '## Analysis: %s (%s)\n\n' "${MODEL_A}" "${ENDPOINT_A}"
  else
    printf '## Analysis: Endpoint A (%s)\n\n' "${ENDPOINT_A}"
  fi
  cat "${TMP}/endpoint_a.md"
  printf '\n\n---\n\n'

  if [ -n "$MODEL_B" ]; then
    printf '## Analysis: %s (%s)\n\n' "${MODEL_B}" "${ENDPOINT_B}"
  else
    printf '## Analysis: Endpoint B (%s)\n\n' "${ENDPOINT_B}"
  fi
  cat "${TMP}/endpoint_b.md"
} > llm-analysis.md

echo "[llm-analysis] Done — written to llm-analysis.md" >&2
