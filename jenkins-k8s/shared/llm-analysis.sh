#!/bin/bash
# LLM failure analysis: calls Ollama and llama.cpp endpoints, merges results into llm-analysis.md
set -euo pipefail

FAILED_STAGE="${FAILED_STAGE:-Unknown}"
ERROR_SNIPPET="${ERROR_SNIPPET:-No error snippet provided}"
LOG_TAIL="${LOG_TAIL:-No log available}"
BUILD_NUMBER="${BUILD_NUMBER:-0}"
JOB_NAME="${JOB_NAME:-unknown}"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

OLLAMA_URL="http://192.168.1.10:11434"
LLAMACPP_URL="http://192.168.1.24:21434"
TIMEOUT=120

PROMPT="You are a CI/CD build failure analyst. Analyze this Android build failure.

Failed Stage: ${FAILED_STAGE}

Error Snippet:
\`\`\`
${ERROR_SNIPPET}
\`\`\`

Console Log Tail (last 200 lines):
\`\`\`
${LOG_TAIL}
\`\`\`

Respond in Markdown with these sections:
## Root Cause
1-2 sentences identifying what broke and why.

## Likely Fix
Numbered steps with code blocks where relevant.

## Confidence
High / Medium / Low with one sentence of reasoning.

## Failure Flow
A Mermaid flowchart showing which step broke and why.
\`\`\`mermaid
flowchart TD
    ...
\`\`\`

## References
Links or notes to relevant docs, Gradle flags, or Android SDK notes."

call_ollama() {
    local model="$1"
    local output_file="$2"
    echo "  → Calling Ollama model: ${model}" >&2
    local payload
    payload=$(jq -n \
        --arg model "$model" \
        --arg prompt "$PROMPT" \
        '{model: $model, prompt: $prompt, stream: false}')
    curl -sf --max-time "$TIMEOUT" \
        -X POST "${OLLAMA_URL}/api/generate" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        | jq -r '.response // "ERROR: empty response from Ollama"' \
        > "$output_file" 2>/dev/null \
        || echo "ERROR: Ollama call failed (timeout or connection refused)" > "$output_file"
}

call_llamacpp() {
    local model="$1"
    local output_file="$2"
    echo "  → Calling llama.cpp model: ${model}" >&2
    local payload
    payload=$(jq -n \
        --arg model "$model" \
        --arg content "$PROMPT" \
        '{model: $model, messages: [{role: "user", content: $content}], stream: false}')
    curl -sf --max-time "$TIMEOUT" \
        -X POST "${LLAMACPP_URL}/v1/chat/completions" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        | jq -r '.choices[0].message.content // "ERROR: empty response from llama.cpp"' \
        > "$output_file" 2>/dev/null \
        || echo "ERROR: llama.cpp call failed (timeout or connection refused)" > "$output_file"
}

model_available_in_ollama() {
    local model="$1"
    curl -sf --max-time 5 "${OLLAMA_URL}/api/tags" \
        | jq -r '.models[].name' 2>/dev/null \
        | grep -q "^${model}" 2>/dev/null
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "[llm-analysis] Starting analysis for: ${JOB_NAME} #${BUILD_NUMBER} — ${FAILED_STAGE}" >&2

echo "[1/3] Qwen2.5-Coder:14B-Instruct (Ollama)" >&2
call_ollama "Qwen2.5-Coder:14B-Instruct" "${TMP}/qwen.md"

echo "[2/3] DeepSeek-Coder-V2-Lite-Instruct (llama.cpp)" >&2
call_llamacpp "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf" "${TMP}/deepseek.md"

echo "[3/3] CodeLlama (Ollama — optional)" >&2
if model_available_in_ollama "codellama"; then
    call_ollama "codellama" "${TMP}/codellama.md"
else
    cat > "${TMP}/codellama.md" <<'EOF'
*Model not available on this Ollama instance.*

To install CodeLlama, run on the Ollama host:
```bash
# 7B variant (~3.8 GB)
curl -X POST http://192.168.1.10:11434/api/pull \
  -H "Content-Type: application/json" \
  -d '{"name": "codellama"}'

# 13B variant — better analysis (~7.4 GB)
curl -X POST http://192.168.1.10:11434/api/pull \
  -H "Content-Type: application/json" \
  -d '{"name": "codellama:13b"}'
```
EOF
fi

cat > llm-analysis.md <<REPORT
# Jenkins Build Failure Analysis

## Build Metadata

| Field | Value |
|---|---|
| **Job** | ${JOB_NAME} |
| **Build #** | ${BUILD_NUMBER} |
| **Failed Stage** | ${FAILED_STAGE} |
| **Timestamp** | ${TIMESTAMP} |

> Mermaid diagrams in the sections below render natively in GitHub, GitLab, VS Code (with Markdown Preview Mermaid Support extension), and Obsidian.

---

## Analysis: Qwen2.5-Coder:14B-Instruct (Ollama — 192.168.1.10:11434)

$(cat "${TMP}/qwen.md")

---

## Analysis: DeepSeek-Coder-V2-Lite-Instruct (llama.cpp — 192.168.1.24:21434)

$(cat "${TMP}/deepseek.md")

---

## Analysis: CodeLlama (Ollama — 192.168.1.10:11434)

$(cat "${TMP}/codellama.md")
REPORT

echo "[llm-analysis] Done — written to llm-analysis.md" >&2
