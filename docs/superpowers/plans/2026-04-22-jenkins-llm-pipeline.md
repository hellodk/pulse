# Jenkins LLM-Assisted Android CI/CD Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy a fully self-hosted Jenkins CI/CD system on Kubernetes (`utilities` namespace) that builds an Android app through a security-hardened pipeline, performs LLM-assisted failure analysis, and emails results to `hello.dk@outlook.com`.

**Architecture:** Two Jenkins deployment reference implementations (Option B: ephemeral K8s agents via Kubernetes plugin; Option C: static persistent JNLP agent) share a common Jenkinsfile, security toolchain, LLM analysis scripts, and email templates. All infrastructure (Postfix, SonarQube, Nexus, PostgreSQL) deploys into the same `utilities` namespace.

**Tech Stack:** Kubernetes, Jenkins LTS, JCasC, Groovy Pipeline, Android SDK (thyrlian/android-sdk), Semgrep, OWASP Dependency-Check, SonarQube Community, Nexus Repository OSS, PostgreSQL 15, Postfix (namshi/smtp), Ollama API, llama.cpp OpenAI-compatible API, email-ext Jenkins plugin.

---

## File Map

```
jenkins-k8s/
├── namespace.yaml
├── Dockerfile                              # Custom Android + tools agent image
├── semgrep-to-html.py                      # Semgrep JSON → HTML converter
│
├── shared/
│   ├── pvcs.yaml                           # All PVCs (all options)
│   ├── postfix-deploy.yaml                 # SMTP relay
│   ├── postgres-deploy.yaml                # PostgreSQL for SonarQube
│   ├── sonarqube-deploy.yaml               # SonarQube Community
│   ├── nexus-deploy.yaml                   # Nexus Repository OSS
│   ├── llm-analysis.sh                     # LLM API caller (Ollama + llama.cpp)
│   ├── check-licenses.sh                   # License compliance checker
│   ├── Jenkinsfile                         # Shared pipeline (both options)
│   ├── failure-email.groovy                # email-ext failure template
│   └── success-email.groovy                # email-ext success template
│
├── option-b-ephemeral-agents/
│   ├── README.md
│   ├── rbac.yaml                           # ServiceAccount + ClusterRole + Binding
│   ├── jenkins-deploy.yaml                 # Jenkins controller Deployment + Service
│   └── jenkins-casc-config.yaml            # JCasC ConfigMap (K8s cloud + credentials)
│
└── option-c-static-agent/
    ├── README.md
    ├── jenkins-deploy.yaml                 # Jenkins controller Deployment + Service
    ├── jenkins-config.yaml                 # ConfigMap: init Groovy scripts
    ├── agent-secret.yaml                   # JNLP agent secret (update after first boot)
    └── jenkins-agent-deploy.yaml           # Android JNLP agent Deployment
```

---

## Phase 1 — Repository Structure + Namespace

### Task 1: Create directory structure and namespace manifest

**Files:**
- Create: `jenkins-k8s/namespace.yaml`

- [ ] **Step 1: Create the project directory tree**

```bash
cd /home/dk/Documents/git/testing-grounds
mkdir -p jenkins-k8s/shared \
         jenkins-k8s/option-b-ephemeral-agents \
         jenkins-k8s/option-c-static-agent
```

- [ ] **Step 2: Write `jenkins-k8s/namespace.yaml`**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: utilities
  labels:
    name: utilities
```

- [ ] **Step 3: Validate and apply**

```bash
kubectl apply --dry-run=client -f jenkins-k8s/namespace.yaml
kubectl apply -f jenkins-k8s/namespace.yaml
kubectl get namespace utilities
```

Expected output: `utilities   Active   Xs`

- [ ] **Step 4: Commit**

```bash
git add jenkins-k8s/
git commit -m "feat: add utilities namespace manifest"
```

---

### Task 2: Create all PersistentVolumeClaims

**Files:**
- Create: `jenkins-k8s/shared/pvcs.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/pvcs.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-b-home
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-c-home
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: gradle-cache
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 5Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: sonarqube-data
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: sonarqube-extensions
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 2Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-data
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 5Gi
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: nexus-data
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 20Gi
```

- [ ] **Step 2: Validate and apply**

```bash
kubectl apply --dry-run=client -f jenkins-k8s/shared/pvcs.yaml
kubectl apply -f jenkins-k8s/shared/pvcs.yaml
kubectl get pvc -n utilities
```

Expected: 7 PVCs in `Pending` or `Bound` state.

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/shared/pvcs.yaml
git commit -m "feat: add PVC manifests for all services"
```

---

## Phase 2 — Shared Infrastructure

### Task 3: Deploy Postfix SMTP relay

**Files:**
- Create: `jenkins-k8s/shared/postfix-deploy.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/postfix-deploy.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postfix
  namespace: utilities
  labels:
    app: postfix
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postfix
  template:
    metadata:
      labels:
        app: postfix
    spec:
      containers:
      - name: postfix
        image: namshi/smtp:latest
        ports:
        - containerPort: 25
        env:
        - name: RELAY_NETWORKS
          value: ":10.0.0.0/8:172.16.0.0/12:192.168.0.0/16"
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "200m"
---
apiVersion: v1
kind: Service
metadata:
  name: smtp
  namespace: utilities
spec:
  selector:
    app: postfix
  ports:
  - port: 25
    targetPort: 25
    protocol: TCP
```

- [ ] **Step 2: Validate and apply**

```bash
kubectl apply --dry-run=client -f jenkins-k8s/shared/postfix-deploy.yaml
kubectl apply -f jenkins-k8s/shared/postfix-deploy.yaml
kubectl rollout status deployment/postfix -n utilities
```

- [ ] **Step 3: Smoke-test SMTP from within the cluster**

```bash
kubectl run smtp-test --image=busybox -n utilities --rm -it --restart=Never -- \
  sh -c "echo 'EHLO test' | nc smtp.utilities.svc.cluster.local 25"
```

Expected: `220 ...ESMTP` banner followed by EHLO response.

- [ ] **Step 4: Commit**

```bash
git add jenkins-k8s/shared/postfix-deploy.yaml
git commit -m "feat: add Postfix SMTP relay"
```

---

### Task 4: Deploy PostgreSQL (SonarQube backend)

**Files:**
- Create: `jenkins-k8s/shared/postgres-deploy.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/postgres-deploy.yaml`**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: sonarqube-db-secret
  namespace: utilities
type: Opaque
stringData:
  POSTGRES_DB: sonarqube
  POSTGRES_USER: sonar
  POSTGRES_PASSWORD: sonar123
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: utilities
  labels:
    app: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:15
        ports:
        - containerPort: 5432
        envFrom:
        - secretRef:
            name: sonarqube-db-secret
        volumeMounts:
        - name: postgres-data
          mountPath: /var/lib/postgresql/data
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        readinessProbe:
          exec:
            command: ["pg_isready", "-U", "sonar"]
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: postgres-data
        persistentVolumeClaim:
          claimName: postgres-data
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: utilities
spec:
  selector:
    app: postgres
  ports:
  - port: 5432
    targetPort: 5432
```

- [ ] **Step 2: Apply and verify**

```bash
kubectl apply -f jenkins-k8s/shared/postgres-deploy.yaml
kubectl rollout status deployment/postgres -n utilities
kubectl exec -n utilities deploy/postgres -- pg_isready -U sonar
```

Expected: `postgres.utilities.svc.cluster.local:5432 - accepting connections`

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/shared/postgres-deploy.yaml
git commit -m "feat: add PostgreSQL deployment for SonarQube"
```

---

### Task 5: Deploy SonarQube Community Edition

**Files:**
- Create: `jenkins-k8s/shared/sonarqube-deploy.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/sonarqube-deploy.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sonarqube
  namespace: utilities
  labels:
    app: sonarqube
spec:
  replicas: 1
  selector:
    matchLabels:
      app: sonarqube
  template:
    metadata:
      labels:
        app: sonarqube
    spec:
      initContainers:
      - name: sysctl
        image: busybox
        securityContext:
          privileged: true
        command:
        - sh
        - -c
        - sysctl -w vm.max_map_count=524288 && sysctl -w fs.file-max=131072
      containers:
      - name: sonarqube
        image: sonarqube:community
        ports:
        - containerPort: 9000
        env:
        - name: SONAR_JDBC_URL
          value: "jdbc:postgresql://postgres.utilities.svc.cluster.local:5432/sonarqube"
        - name: SONAR_JDBC_USERNAME
          valueFrom:
            secretKeyRef:
              name: sonarqube-db-secret
              key: POSTGRES_USER
        - name: SONAR_JDBC_PASSWORD
          valueFrom:
            secretKeyRef:
              name: sonarqube-db-secret
              key: POSTGRES_PASSWORD
        volumeMounts:
        - name: sonarqube-data
          mountPath: /opt/sonarqube/data
        - name: sonarqube-extensions
          mountPath: /opt/sonarqube/extensions
        resources:
          requests:
            memory: "2Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2"
        livenessProbe:
          httpGet:
            path: /api/system/status
            port: 9000
          initialDelaySeconds: 90
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /api/system/status
            port: 9000
          initialDelaySeconds: 60
          periodSeconds: 10
      volumes:
      - name: sonarqube-data
        persistentVolumeClaim:
          claimName: sonarqube-data
      - name: sonarqube-extensions
        persistentVolumeClaim:
          claimName: sonarqube-extensions
---
apiVersion: v1
kind: Service
metadata:
  name: sonarqube
  namespace: utilities
spec:
  selector:
    app: sonarqube
  type: NodePort
  ports:
  - port: 9000
    targetPort: 9000
    nodePort: 30900
```

- [ ] **Step 2: Apply and wait for SonarQube to be ready (takes ~2 minutes)**

```bash
kubectl apply -f jenkins-k8s/shared/sonarqube-deploy.yaml
kubectl rollout status deployment/sonarqube -n utilities --timeout=300s
```

- [ ] **Step 3: Verify SonarQube is UP**

```bash
kubectl exec -n utilities deploy/sonarqube -- \
  curl -s http://localhost:9000/api/system/status | grep -o '"status":"[A-Z]*"'
```

Expected: `"status":"UP"`

- [ ] **Step 4: Generate SonarQube token for Jenkins**

```bash
# Port-forward to access UI (run in background)
kubectl port-forward -n utilities svc/sonarqube 9000:9000 &

# Generate token via API (default credentials: admin/admin — change on first login)
curl -u admin:admin -X POST \
  "http://localhost:9000/api/user_tokens/generate" \
  -d "name=jenkins-token" | jq -r '.token'
```

Save the token — you will paste it into JCasC config in Task 8 (Option B) or Task 11 (Option C).

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/shared/sonarqube-deploy.yaml
git commit -m "feat: add SonarQube Community Edition deployment"
```

---

### Task 6: Deploy Nexus Repository OSS

**Files:**
- Create: `jenkins-k8s/shared/nexus-deploy.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/nexus-deploy.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nexus
  namespace: utilities
  labels:
    app: nexus
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nexus
  template:
    metadata:
      labels:
        app: nexus
    spec:
      securityContext:
        fsGroup: 200
      containers:
      - name: nexus
        image: sonatype/nexus3:latest
        ports:
        - containerPort: 8081
        volumeMounts:
        - name: nexus-data
          mountPath: /nexus-data
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2"
        readinessProbe:
          httpGet:
            path: /service/rest/v1/status
            port: 8081
          initialDelaySeconds: 90
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /service/rest/v1/status
            port: 8081
          initialDelaySeconds: 120
          periodSeconds: 30
      volumes:
      - name: nexus-data
        persistentVolumeClaim:
          claimName: nexus-data
---
apiVersion: v1
kind: Service
metadata:
  name: nexus
  namespace: utilities
spec:
  selector:
    app: nexus
  type: NodePort
  ports:
  - port: 8081
    targetPort: 8081
    nodePort: 30081
```

- [ ] **Step 2: Apply and wait**

```bash
kubectl apply -f jenkins-k8s/shared/nexus-deploy.yaml
kubectl rollout status deployment/nexus -n utilities --timeout=300s
```

- [ ] **Step 3: Get Nexus admin password and create `android-releases` repository**

```bash
# Get initial admin password
kubectl exec -n utilities deploy/nexus -- cat /nexus-data/admin.password
echo ""

# Port-forward
kubectl port-forward -n utilities svc/nexus 8081:8081 &

# Create raw hosted repository via API (replace INITIAL_PASSWORD)
curl -u admin:INITIAL_PASSWORD \
  -X POST "http://localhost:8081/service/rest/v1/repositories/raw/hosted" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "android-releases",
    "online": true,
    "storage": {
      "blobStoreName": "default",
      "strictContentTypeValidation": false,
      "writePolicy": "ALLOW"
    }
  }'
```

Expected: `HTTP 201 Created`

- [ ] **Step 4: Commit**

```bash
git add jenkins-k8s/shared/nexus-deploy.yaml
git commit -m "feat: add Nexus Repository OSS deployment"
```

---

## Phase 3 — Custom Android Agent Docker Image

### Task 7: Write Dockerfile and helper scripts

**Files:**
- Create: `jenkins-k8s/Dockerfile`
- Create: `jenkins-k8s/semgrep-to-html.py`

- [ ] **Step 1: Write `jenkins-k8s/Dockerfile`**

```dockerfile
FROM thyrlian/android-sdk:latest

USER root

RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    python3 \
    python3-pip \
    jq \
    zip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Semgrep
RUN pip3 install semgrep

# OWASP Dependency-Check 9.0.9
RUN wget -q \
    "https://github.com/jeremylong/DependencyCheck/releases/download/v9.0.9/dependency-check-9.0.9-release.zip" \
    -O /tmp/dependency-check.zip \
    && unzip /tmp/dependency-check.zip -d /opt/ \
    && rm /tmp/dependency-check.zip \
    && chmod +x /opt/dependency-check/bin/dependency-check.sh
ENV PATH="/opt/dependency-check/bin:$PATH"

# Jenkins JNLP agent jar (for Option C)
ARG REMOTING_VERSION=3248.3250.v3277a_8e88c9b_
RUN curl -sL \
    "https://repo.jenkins-ci.org/releases/org/jenkins-ci/main/remoting/${REMOTING_VERSION}/remoting-${REMOTING_VERSION}.jar" \
    -o /usr/share/jenkins/agent.jar \
    && chmod 644 /usr/share/jenkins/agent.jar

COPY semgrep-to-html.py /opt/semgrep-to-html.py
COPY shared/llm-analysis.sh /opt/llm-analysis.sh
COPY shared/check-licenses.sh /opt/check-licenses.sh
RUN chmod +x /opt/llm-analysis.sh /opt/check-licenses.sh

RUN useradd -d /home/jenkins -m -s /bin/bash jenkins 2>/dev/null || true \
    && mkdir -p /home/jenkins/agent \
    && chown -R jenkins:jenkins /home/jenkins

USER jenkins
WORKDIR /home/jenkins/agent

# Option C entrypoint: override with JNLP agent command when used as static agent
CMD ["bash"]
```

- [ ] **Step 2: Write `jenkins-k8s/semgrep-to-html.py`**

```python
#!/usr/bin/env python3
import json
import sys
from html import escape

def convert(json_file, html_file):
    with open(json_file) as f:
        data = json.load(f)

    results = data.get('results', [])
    errors  = data.get('errors', [])

    rows = ""
    for r in results:
        sev  = escape(r.get('extra', {}).get('severity', 'INFO').upper())
        rule = escape(r.get('check_id', ''))
        path = escape(r.get('path', ''))
        line = str(r.get('start', {}).get('line', ''))
        msg  = escape(r.get('extra', {}).get('message', ''))
        rows += f"<tr class='{sev}'><td>{sev}</td><td>{rule}</td><td>{path}:{line}</td><td>{msg}</td></tr>\n"

    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Semgrep SAST Report</title>
<style>
  body {{font-family:Arial,sans-serif;margin:2em;}}
  h1 {{color:#333;}}
  table {{border-collapse:collapse;width:100%;}}
  th,td {{border:1px solid #ddd;padding:8px;text-align:left;font-size:13px;}}
  th {{background:#4a4a4a;color:white;}}
  .ERROR {{background:#f8d7da;}}
  .WARNING {{background:#fff3cd;}}
  .INFO {{background:#d1ecf1;}}
</style>
</head>
<body>
<h1>Semgrep SAST Report</h1>
<p>Total findings: <strong>{len(results)}</strong> &nbsp;|&nbsp; Parse errors: {len(errors)}</p>
<table>
<tr><th>Severity</th><th>Rule</th><th>File:Line</th><th>Message</th></tr>
{rows}
</table>
</body>
</html>"""

    with open(html_file, 'w') as f:
        f.write(html)
    print(f"Report written to {html_file} ({len(results)} findings)")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.json> <output.html>")
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2])
```

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/Dockerfile jenkins-k8s/semgrep-to-html.py
git commit -m "feat: add custom Android agent Dockerfile and Semgrep HTML converter"
```

> **Note:** The Dockerfile copies `shared/llm-analysis.sh` and `shared/check-licenses.sh`
> which are written in Tasks 13 and 14. Build the image after those tasks are complete.

---

### Task 8: Write LLM analysis script

**Files:**
- Create: `jenkins-k8s/shared/llm-analysis.sh`

- [ ] **Step 1: Write `jenkins-k8s/shared/llm-analysis.sh`**

```bash
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
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x jenkins-k8s/shared/llm-analysis.sh
```

- [ ] **Step 3: Test with mock inputs**

```bash
FAILED_STAGE="Build Debug APK" \
ERROR_SNIPPET="FAILURE: Build failed with an exception.\n* What went wrong:\nExecution failed for task ':app:compileDebugKotlin'.\n> Kotlin compiler daemon went out of memory" \
LOG_TAIL="$(printf 'fake log line\n%.0s' {1..200})" \
BUILD_NUMBER="99" \
JOB_NAME="android-build-test" \
bash jenkins-k8s/shared/llm-analysis.sh

ls -la llm-analysis.md && head -30 llm-analysis.md
```

Expected: `llm-analysis.md` written with 3 analysis sections.

- [ ] **Step 4: Commit**

```bash
git add jenkins-k8s/shared/llm-analysis.sh
git commit -m "feat: add LLM failure analysis script (Ollama + llama.cpp)"
```

---

### Task 9: Write license compliance checker

**Files:**
- Create: `jenkins-k8s/shared/check-licenses.sh`

- [ ] **Step 1: Write `jenkins-k8s/shared/check-licenses.sh`**

```bash
#!/bin/bash
# check-licenses.sh <license-json-dir> <output-html>
# Reads Gradle License Plugin JSON output, generates color-coded HTML report.
# Exit code 1 if any BLOCKED licenses found.
set -euo pipefail

INPUT_DIR="${1:-build/reports/licenses}"
OUTPUT_HTML="${2:-reports/license-report.html}"

ALLOWED="MIT Apache-2.0 BSD-2-Clause BSD-3-Clause ISC Unlicense CC0-1.0 Apache-1.1"
REVIEW="LGPL-2.0 LGPL-2.1 LGPL-3.0 MPL-2.0 CDDL-1.0 EPL-1.0 EPL-2.0 EUPL-1.2"
BLOCKED="AGPL-3.0 AGPL-1.0 GPL-2.0 GPL-3.0 SSPL-1.0 BUSL-1.1"

FAIL=0
TOTAL=0
ALLOWED_COUNT=0
REVIEW_COUNT=0
BLOCKED_COUNT=0

declare -A DEP_LICENSE
declare -A DEP_URL

# Parse Gradle License Plugin JSON (format: [{name, license, licenseUrl},...])
if ls "${INPUT_DIR}"/*.json &>/dev/null 2>&1; then
    while IFS= read -r entry; do
        dep=$(echo "$entry" | jq -r '.project // .name // "unknown"')
        lic=$(echo "$entry" | jq -r '.license // .spdxLicense // "Unknown"')
        url=$(echo "$entry" | jq -r '.licenseUrl // ""')
        DEP_LICENSE["$dep"]="$lic"
        DEP_URL["$dep"]="$url"
    done < <(jq -c '.[]' "${INPUT_DIR}"/*.json 2>/dev/null || true)
fi

mkdir -p "$(dirname "$OUTPUT_HTML")"

rows=""
for dep in "${!DEP_LICENSE[@]}"; do
    lic="${DEP_LICENSE[$dep]}"
    url="${DEP_URL[$dep]}"
    class="amber"
    status="Review Required"
    ((TOTAL++)) || true

    if echo " $BLOCKED " | grep -qw " $lic "; then
        class="red"; status="BLOCKED — Copyleft Risk"; ((BLOCKED_COUNT++)); FAIL=1
    elif echo " $ALLOWED " | grep -qw " $lic "; then
        class="green"; status="Allowed"; ((ALLOWED_COUNT++))
    else
        ((REVIEW_COUNT++))
    fi

    link="${dep}"
    [ -n "$url" ] && link="<a href='${url}' target='_blank'>${dep}</a>"
    rows+="<tr class='${class}'><td>${link}</td><td>${lic}</td><td>${status}</td></tr>\n"
done

cat > "$OUTPUT_HTML" <<HTML
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>License Compliance Report</title>
<style>
  body{font-family:Arial,sans-serif;margin:2em;}
  h1{color:#333;}
  .summary{display:flex;gap:16px;margin-bottom:16px;}
  .badge{padding:8px 16px;border-radius:4px;font-weight:bold;font-size:14px;}
  .badge-green{background:#d4edda;color:#155724;}
  .badge-amber{background:#fff3cd;color:#856404;}
  .badge-red{background:#f8d7da;color:#721c24;}
  table{border-collapse:collapse;width:100%;}
  th,td{border:1px solid #ddd;padding:8px;text-align:left;font-size:13px;}
  th{background:#4a4a4a;color:white;}
  .green{background:#d4edda;}
  .amber{background:#fff3cd;}
  .red{background:#f8d7da;font-weight:bold;}
  a{color:#0066cc;}
</style>
</head>
<body>
<h1>License Compliance Report</h1>
<p>Generated: $(date -u)</p>
<div class="summary">
  <div class="badge badge-green">Allowed: ${ALLOWED_COUNT}</div>
  <div class="badge badge-amber">Review: ${REVIEW_COUNT}</div>
  <div class="badge badge-red">Blocked: ${BLOCKED_COUNT}</div>
</div>
<table>
<tr><th>Dependency</th><th>License</th><th>Status</th></tr>
$(echo -e "$rows")
</table>
<hr/>
<p style="font-size:12px;color:#666;">
  <strong>Blocked licenses (fail build):</strong> AGPL-3.0, AGPL-1.0, GPL-2.0, GPL-3.0, SSPL-1.0, BUSL-1.1<br/>
  <strong>Review licenses (warn):</strong> LGPL, MPL-2.0, CDDL, EPL<br/>
  <strong>Allowed licenses (pass):</strong> MIT, Apache-2.0, BSD, ISC, Unlicense, CC0
</p>
</body>
</html>
HTML

echo "License report: ${TOTAL} total, ${ALLOWED_COUNT} allowed, ${REVIEW_COUNT} review, ${BLOCKED_COUNT} blocked"
[ $FAIL -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL (blocked licenses found)"
exit $FAIL
```

- [ ] **Step 2: Make executable and test**

```bash
chmod +x jenkins-k8s/shared/check-licenses.sh

# Create a mock license JSON to test with
mkdir -p /tmp/mock-licenses
cat > /tmp/mock-licenses/licenses.json <<'JSON'
[
  {"project": "retrofit:2.9.0", "license": "Apache-2.0", "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0"},
  {"project": "gson:2.10", "license": "Apache-2.0", "licenseUrl": ""},
  {"project": "some-gpl-lib:1.0", "license": "GPL-3.0", "licenseUrl": ""},
  {"project": "okhttp:4.12.0", "license": "Apache-2.0", "licenseUrl": ""}
]
JSON

mkdir -p /tmp/test-reports
bash jenkins-k8s/shared/check-licenses.sh /tmp/mock-licenses /tmp/test-reports/license-report.html
echo "Exit code: $?"
```

Expected output:
```
License report: 4 total, 3 allowed, 0 review, 1 blocked
RESULT: FAIL (blocked licenses found)
Exit code: 1
```

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/shared/check-licenses.sh
git commit -m "feat: add license compliance checker script"
```

---

### Task 10: Build and push custom Android agent image

> **Prerequisite:** Tasks 8 and 9 must be complete (scripts are COPY-ed into the image).

- [ ] **Step 1: Build the image**

```bash
cd jenkins-k8s/
docker build -t android-jenkins-agent:latest .
cd ..
```

Expected: `Successfully built <image-id>` and `Successfully tagged android-jenkins-agent:latest`

- [ ] **Step 2: Verify tools are present in the image**

```bash
docker run --rm android-jenkins-agent:latest semgrep --version
docker run --rm android-jenkins-agent:latest dependency-check.sh --version
docker run --rm android-jenkins-agent:latest jq --version
docker run --rm android-jenkins-agent:latest java -version
```

All commands should exit 0 with version output.

- [ ] **Step 3: Push to your cluster-accessible registry**

```bash
# Tag and push to your local registry (replace REGISTRY with your registry address)
# Common options: localhost:5000, a Harbor instance, or any registry accessible from cluster nodes
docker tag android-jenkins-agent:latest REGISTRY/android-jenkins-agent:latest
docker push REGISTRY/android-jenkins-agent:latest
```

> If you don't have a registry, load directly into cluster nodes:
> ```bash
> docker save android-jenkins-agent:latest | \
>   kubectl debug node/<node-name> -it --image=busybox -- \
>   sh -c "cat > /tmp/img.tar && ctr images import /tmp/img.tar"
> ```
> Or for k3s: `docker save android-jenkins-agent:latest | k3s ctr images import -`

- [ ] **Step 4: Update image reference in JCasC/agent manifests**

After pushing, update the image field in:
- `jenkins-k8s/option-b-ephemeral-agents/jenkins-casc-config.yaml` — line with `image: "android-jenkins-agent:latest"` → use full registry path
- `jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml` — same

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/
git commit -m "docs: note registry push step for Android agent image"
```

---

## Phase 4 — Option B: Ephemeral K8s Agents

### Task 11: Deploy Option B RBAC

**Files:**
- Create: `jenkins-k8s/option-b-ephemeral-agents/rbac.yaml`

- [ ] **Step 1: Write `jenkins-k8s/option-b-ephemeral-agents/rbac.yaml`**

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins
  namespace: utilities
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-agent-role
rules:
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list", "create", "delete", "watch", "patch", "update"]
- apiGroups: [""]
  resources: ["pods/exec"]
  verbs: ["create", "get"]
- apiGroups: [""]
  resources: ["pods/log"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get"]
- apiGroups: [""]
  resources: ["events"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["persistentvolumeclaims"]
  verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jenkins-agent-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-agent-role
subjects:
- kind: ServiceAccount
  name: jenkins
  namespace: utilities
```

- [ ] **Step 2: Apply and verify**

```bash
kubectl apply -f jenkins-k8s/option-b-ephemeral-agents/rbac.yaml
kubectl get serviceaccount jenkins -n utilities
kubectl get clusterrolebinding jenkins-agent-binding
```

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/option-b-ephemeral-agents/rbac.yaml
git commit -m "feat: add Jenkins RBAC for Option B ephemeral agents"
```

---

### Task 12: Deploy Option B Jenkins controller

**Files:**
- Create: `jenkins-k8s/option-b-ephemeral-agents/jenkins-deploy.yaml`
- Create: `jenkins-k8s/option-b-ephemeral-agents/jenkins-casc-config.yaml`

- [ ] **Step 1: Write `jenkins-k8s/option-b-ephemeral-agents/jenkins-deploy.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins-b
  namespace: utilities
  labels:
    app: jenkins-b
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins-b
  template:
    metadata:
      labels:
        app: jenkins-b
    spec:
      serviceAccountName: jenkins
      securityContext:
        fsGroup: 1000
        runAsUser: 1000
      initContainers:
      - name: install-plugins
        image: jenkins/jenkins:lts
        command:
        - sh
        - -c
        - |
          jenkins-plugin-cli --plugins \
            kubernetes:latest \
            workflow-aggregator:latest \
            git:latest \
            configuration-as-code:latest \
            sonar:latest \
            dependency-check-jenkins-plugin:latest \
            warnings-ng:latest \
            htmlpublisher:latest \
            email-ext:latest \
            jacoco:latest \
            pipeline-stage-view:latest
        volumeMounts:
        - name: jenkins-home
          mountPath: /var/jenkins_home
      containers:
      - name: jenkins
        image: jenkins/jenkins:lts
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 50000
          name: jnlp
        env:
        - name: JAVA_OPTS
          value: >-
            -Djenkins.install.runSetupWizard=false
            -Dcasc.jenkins.config=/var/jenkins_casc/
        - name: CASC_JENKINS_CONFIG
          value: "/var/jenkins_casc/"
        volumeMounts:
        - name: jenkins-home
          mountPath: /var/jenkins_home
        - name: casc-config
          mountPath: /var/jenkins_casc
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "3Gi"
            cpu: "2"
        readinessProbe:
          httpGet:
            path: /login
            port: 8080
          initialDelaySeconds: 90
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /login
            port: 8080
          initialDelaySeconds: 120
          periodSeconds: 30
      volumes:
      - name: jenkins-home
        persistentVolumeClaim:
          claimName: jenkins-b-home
      - name: casc-config
        configMap:
          name: jenkins-b-casc
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins-b
  namespace: utilities
spec:
  selector:
    app: jenkins-b
  type: NodePort
  ports:
  - name: http
    port: 8080
    targetPort: 8080
    nodePort: 30880
  - name: jnlp
    port: 50000
    targetPort: 50000
```

- [ ] **Step 2: Write `jenkins-k8s/option-b-ephemeral-agents/jenkins-casc-config.yaml`**

> **Replace `SONAR_TOKEN_HERE` and `NEXUS_PASS_HERE`** with the values retrieved in Tasks 5 and 6 before applying.
> **Replace `REGISTRY/android-jenkins-agent:latest`** with your actual image path from Task 10.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jenkins-b-casc
  namespace: utilities
data:
  jenkins.yaml: |
    jenkins:
      systemMessage: "Jenkins CI/CD — Option B (Ephemeral K8s Agents)"
      numExecutors: 0
      mode: NORMAL

      clouds:
        - kubernetes:
            name: "kubernetes"
            serverUrl: "https://kubernetes.default.svc"
            namespace: "utilities"
            jenkinsUrl: "http://jenkins-b.utilities.svc.cluster.local:8080"
            connectTimeout: 5
            readTimeout: 15
            containerCapStr: "10"
            templates:
              - name: "android-agent"
                label: "android-agent"
                nodeUsageMode: EXCLUSIVE
                idleMinutes: 0
                containers:
                  - name: "jnlp"
                    image: "REGISTRY/android-jenkins-agent:latest"
                    workingDir: "/home/jenkins/agent"
                    resourceRequestCpu: "1000m"
                    resourceRequestMemory: "2048Mi"
                    resourceLimitCpu: "2000m"
                    resourceLimitMemory: "4096Mi"
                    envVars:
                      - envVar:
                          key: "ANDROID_HOME"
                          value: "/opt/android-sdk"
                volumes:
                  - emptyDirVolume:
                      mountPath: "/root/.gradle"
                      memory: false

    credentials:
      system:
        domainCredentials:
          - credentials:
              - usernamePassword:
                  id: "nexus-creds"
                  scope: GLOBAL
                  username: "admin"
                  password: "NEXUS_PASS_HERE"
                  description: "Nexus Repository OSS credentials"
              - string:
                  id: "sonar-token"
                  scope: GLOBAL
                  secret: "SONAR_TOKEN_HERE"
                  description: "SonarQube authentication token"

    unclassified:
      mailer:
        smtpHost: "smtp.utilities.svc.cluster.local"
        smtpPort: "25"
        replyToAddress: "jenkins@utilities.cluster.local"

      sonarGlobalConfiguration:
        buildWrapperEnabled: true
        installations:
          - name: "SonarQube"
            serverUrl: "http://sonarqube.utilities.svc.cluster.local:9000"
            credentialsId: "sonar-token"

      extendedEmailPublisher:
        defaultContentType: "text/html"
        defaultRecipients: "hello.dk@outlook.com"
        defaultSubject: "Jenkins Build Notification"
        mailAccount:
          smtpHost: "smtp.utilities.svc.cluster.local"
          smtpPort: "25"

    tool:
      git:
        installations:
          - name: Default
            home: git
```

- [ ] **Step 3: Apply and verify**

```bash
kubectl apply -f jenkins-k8s/option-b-ephemeral-agents/jenkins-casc-config.yaml
kubectl apply -f jenkins-k8s/option-b-ephemeral-agents/jenkins-deploy.yaml
kubectl rollout status deployment/jenkins-b -n utilities --timeout=300s
```

- [ ] **Step 4: Verify Jenkins is reachable**

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
curl -s -o /dev/null -w "%{http_code}" "http://${NODE_IP}:30880/login"
```

Expected: `200`

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/option-b-ephemeral-agents/
git commit -m "feat: deploy Jenkins Option B (ephemeral K8s agents) with JCasC"
```

---

### Task 13: Write Option B README

**Files:**
- Create: `jenkins-k8s/option-b-ephemeral-agents/README.md`

- [ ] **Step 1: Write `jenkins-k8s/option-b-ephemeral-agents/README.md`**

```markdown
# Option B — Jenkins with Ephemeral Kubernetes Agents

## What This Is
Jenkins controller + Kubernetes plugin. Each build spawns a fresh Android SDK pod, runs all
stages, then the pod is destroyed. No persistent agent state between builds.

## Prerequisites
- `utilities` namespace exists
- Shared infrastructure deployed (postfix, sonarqube, postgres, nexus, pvcs)
- Custom Android agent image pushed to registry accessible from cluster nodes
- SonarQube token and Nexus password retrieved (see Tasks 5 and 6 in plan)

## Deploy

```bash
# 1. Apply RBAC
kubectl apply -f rbac.yaml

# 2. Update jenkins-casc-config.yaml with your SonarQube token, Nexus password, and image registry path

# 3. Apply ConfigMap and Deployment
kubectl apply -f jenkins-casc-config.yaml
kubectl apply -f jenkins-deploy.yaml

# 4. Wait
kubectl rollout status deployment/jenkins-b -n utilities --timeout=300s
```

## Access

- **Jenkins UI:** `http://<node-ip>:30880`
- **Default admin credentials:** configured via JCasC in `jenkins-casc-config.yaml`

## Creating the Pipeline Job

1. Open Jenkins UI → New Item → Pipeline → name it `android-build`
2. In Pipeline section, select **Pipeline script from SCM**
3. SCM: Git, Repository URL: path to this repo
4. Script Path: `jenkins-k8s/shared/Jenkinsfile`
5. Save and Build Now

## How Agent Pods Work

- Jenkins controller creates a pod in `utilities` namespace for each build
- Pod uses label `android-agent` matching the JCasC pod template
- Pod is deleted immediately after the build completes (idleMinutes: 0)
- Gradle cache uses an emptyDir (reset per build) — builds always download deps fresh
  - To cache: replace emptyDir with a PVC mount (same as Option C gradle-cache)

## Updating Credentials After First Deploy

JCasC sets initial credentials. To rotate without redeploying:
1. Jenkins UI → Manage Jenkins → Credentials
2. Update the relevant credential value
3. Or update the ConfigMap and restart: `kubectl rollout restart deployment/jenkins-b -n utilities`

## Key Differences vs Option C

| | Option B (this) | Option C |
|---|---|---|
| Agent lifecycle | Per-build pod | Always-on pod |
| Gradle cache | Empty per build | Persistent PVC |
| First build time | Slower (deps download) | Faster after first build |
| Isolation | Complete (fresh pod) | Shared state possible |
| RBAC required | Yes | No |
```

- [ ] **Step 2: Commit**

```bash
git add jenkins-k8s/option-b-ephemeral-agents/README.md
git commit -m "docs: add Option B deployment README"
```

---

## Phase 5 — Option C: Static JNLP Agent

### Task 14: Deploy Option C Jenkins controller

**Files:**
- Create: `jenkins-k8s/option-c-static-agent/jenkins-deploy.yaml`
- Create: `jenkins-k8s/option-c-static-agent/jenkins-config.yaml`

- [ ] **Step 1: Write `jenkins-k8s/option-c-static-agent/jenkins-config.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jenkins-c-init
  namespace: utilities
data:
  01-security.groovy: |
    import jenkins.model.*
    import hudson.security.*

    def instance = Jenkins.getInstance()
    def realm = new HudsonPrivateSecurityRealm(false)
    realm.createAccount("admin", "admin123")
    instance.setSecurityRealm(realm)

    def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
    strategy.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(strategy)
    instance.save()

  02-node.groovy: |
    import jenkins.model.*
    import hudson.model.*
    import hudson.slaves.*

    def instance = Jenkins.getInstance()
    def nodeName = "android-agent"
    if (instance.getNode(nodeName) == null) {
      def slave = new DumbSlave(
        nodeName,
        "Android Build Agent — Option C static JNLP",
        "/home/jenkins/agent",
        "2",
        Node.Mode.EXCLUSIVE,
        "android-agent",
        new JNLPLauncher(true),
        new RetentionStrategy.Always()
      )
      instance.addNode(slave)
      instance.save()
      println "Created node: ${nodeName}"
    } else {
      println "Node already exists: ${nodeName}"
    }

  03-smtp.groovy: |
    import jenkins.model.*
    import hudson.tasks.Mailer

    def desc = Jenkins.getInstance().getDescriptor("hudson.tasks.Mailer")
    desc.setSmtpHost("smtp.utilities.svc.cluster.local")
    desc.setSmtpPort("25")
    desc.save()
    println "SMTP configured"

  04-sonarqube.groovy: |
    import jenkins.model.*
    import hudson.plugins.sonar.*
    import hudson.plugins.sonar.model.*
    import com.cloudbees.plugins.credentials.*
    import com.cloudbees.plugins.credentials.domains.*
    import org.jenkinsci.plugins.plaincredentials.impl.*
    import hudson.util.Secret

    def instance = Jenkins.getInstance()

    // Add SonarQube token credential
    def credStore = SystemCredentialsProvider.getInstance().getStore()
    def sonarCred = new StringCredentialsImpl(
        CredentialsScope.GLOBAL,
        "sonar-token",
        "SonarQube authentication token",
        Secret.fromString("SONAR_TOKEN_HERE")   // Replace with actual token from Task 5
    )
    credStore.addCredentials(Domain.global(), sonarCred)

    // Add Nexus credentials
    def nexusCred = new UsernamePasswordCredentialsImpl(
        CredentialsScope.GLOBAL,
        "nexus-creds",
        "Nexus Repository OSS credentials",
        "admin",
        "NEXUS_PASS_HERE"   // Replace with actual Nexus password from Task 6
    )
    credStore.addCredentials(Domain.global(), nexusCred)

    // Configure SonarQube server
    def sonarDesc = instance.getDescriptorByType(SonarGlobalConfiguration.class)
    def sonarInstall = new SonarInstallation(
        "SonarQube",
        "http://sonarqube.utilities.svc.cluster.local:9000",
        "sonar-token",
        "", "", "", "", "", null
    )
    sonarDesc.setInstallations(sonarInstall)
    sonarDesc.save()
    println "SonarQube configured"
```

> **Before applying:** Replace `SONAR_TOKEN_HERE` and `NEXUS_PASS_HERE` in `04-sonarqube.groovy`.

- [ ] **Step 2: Write `jenkins-k8s/option-c-static-agent/jenkins-deploy.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins-c
  namespace: utilities
  labels:
    app: jenkins-c
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins-c
  template:
    metadata:
      labels:
        app: jenkins-c
    spec:
      securityContext:
        fsGroup: 1000
        runAsUser: 1000
      initContainers:
      - name: install-plugins
        image: jenkins/jenkins:lts
        command:
        - sh
        - -c
        - |
          jenkins-plugin-cli --plugins \
            workflow-aggregator:latest \
            git:latest \
            sonar:latest \
            dependency-check-jenkins-plugin:latest \
            warnings-ng:latest \
            htmlpublisher:latest \
            email-ext:latest \
            jacoco:latest \
            pipeline-stage-view:latest
        volumeMounts:
        - name: jenkins-home
          mountPath: /var/jenkins_home
      containers:
      - name: jenkins
        image: jenkins/jenkins:lts
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 50000
          name: jnlp
        env:
        - name: JAVA_OPTS
          value: "-Djenkins.install.runSetupWizard=false"
        volumeMounts:
        - name: jenkins-home
          mountPath: /var/jenkins_home
        - name: init-scripts
          mountPath: /var/jenkins_home/init.groovy.d
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "3Gi"
            cpu: "2"
        readinessProbe:
          httpGet:
            path: /login
            port: 8080
          initialDelaySeconds: 90
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /login
            port: 8080
          initialDelaySeconds: 120
          periodSeconds: 30
      volumes:
      - name: jenkins-home
        persistentVolumeClaim:
          claimName: jenkins-c-home
      - name: init-scripts
        configMap:
          name: jenkins-c-init
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins-c
  namespace: utilities
spec:
  selector:
    app: jenkins-c
  type: NodePort
  ports:
  - name: http
    port: 8080
    targetPort: 8080
    nodePort: 30881
  - name: jnlp
    port: 50000
    targetPort: 50000
    nodePort: 30500
```

- [ ] **Step 3: Apply controller only (agent comes after we retrieve JNLP secret)**

```bash
kubectl apply -f jenkins-k8s/option-c-static-agent/jenkins-config.yaml
kubectl apply -f jenkins-k8s/option-c-static-agent/jenkins-deploy.yaml
kubectl rollout status deployment/jenkins-c -n utilities --timeout=300s
```

- [ ] **Step 4: Commit**

```bash
git add jenkins-k8s/option-c-static-agent/jenkins-deploy.yaml \
        jenkins-k8s/option-c-static-agent/jenkins-config.yaml
git commit -m "feat: deploy Jenkins Option C controller with static agent node pre-seed"
```

---

### Task 15: Deploy Option C static JNLP agent

**Files:**
- Create: `jenkins-k8s/option-c-static-agent/agent-secret.yaml`
- Create: `jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml`

- [ ] **Step 1: Retrieve the JNLP agent secret from Jenkins**

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
echo "Open: http://${NODE_IP}:30881"
echo "Navigate to: Manage Jenkins → Nodes → android-agent → (status page)"
echo "Copy the secret token shown on that page."
```

- [ ] **Step 2: Write `jenkins-k8s/option-c-static-agent/agent-secret.yaml`**

Replace `REPLACE_WITH_ACTUAL_SECRET` with the token from Step 1.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: jenkins-agent-secret
  namespace: utilities
type: Opaque
stringData:
  JENKINS_SECRET: "REPLACE_WITH_ACTUAL_SECRET"
```

- [ ] **Step 3: Write `jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml`**

> Replace `REGISTRY/android-jenkins-agent:latest` with your actual image path from Task 10.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: android-build-agent
  namespace: utilities
  labels:
    app: android-build-agent
spec:
  replicas: 1
  selector:
    matchLabels:
      app: android-build-agent
  template:
    metadata:
      labels:
        app: android-build-agent
    spec:
      containers:
      - name: agent
        image: REGISTRY/android-jenkins-agent:latest
        command:
        - java
        - -jar
        - /usr/share/jenkins/agent.jar
        - -url
        - http://jenkins-c.utilities.svc.cluster.local:8080
        - -name
        - android-agent
        - -secret
        - $(JENKINS_SECRET)
        - -workDir
        - /home/jenkins/agent
        env:
        - name: JENKINS_SECRET
          valueFrom:
            secretKeyRef:
              name: jenkins-agent-secret
              key: JENKINS_SECRET
        - name: ANDROID_HOME
          value: "/opt/android-sdk"
        volumeMounts:
        - name: gradle-cache
          mountPath: /root/.gradle
        resources:
          requests:
            memory: "2Gi"
            cpu: "1"
          limits:
            memory: "4Gi"
            cpu: "2"
      volumes:
      - name: gradle-cache
        persistentVolumeClaim:
          claimName: gradle-cache
```

- [ ] **Step 4: Apply and verify agent connects**

```bash
kubectl apply -f jenkins-k8s/option-c-static-agent/agent-secret.yaml
kubectl apply -f jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml
kubectl rollout status deployment/android-build-agent -n utilities

# Check agent connected in Jenkins
sleep 30
kubectl logs -n utilities deploy/android-build-agent | tail -5
```

Expected log line: `INFO: Connected`

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/option-c-static-agent/agent-secret.yaml \
        jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml
git commit -m "feat: deploy Option C static JNLP Android build agent"
```

---

### Task 16: Write Option C README

**Files:**
- Create: `jenkins-k8s/option-c-static-agent/README.md`

- [ ] **Step 1: Write `jenkins-k8s/option-c-static-agent/README.md`**

```markdown
# Option C — Jenkins with Static JNLP Agent

## What This Is
Jenkins controller + a permanently running Android SDK agent pod that connects via JNLP
(outbound from agent → controller:50000). The agent stays alive between builds, enabling
a persistent Gradle cache that speeds up subsequent builds significantly.

## Prerequisites
- `utilities` namespace exists
- Shared infrastructure deployed (postfix, sonarqube, postgres, nexus, pvcs)
- Custom Android agent image pushed to registry accessible from cluster nodes
- SonarQube token and Nexus password retrieved (see Tasks 5 and 6 in plan)
- `jenkins-c-home` and `gradle-cache` PVCs bound

## Deploy Order (IMPORTANT — two-step)

### Step 1: Deploy the controller

```bash
# Update jenkins-config.yaml with your SonarQube token and Nexus password first
kubectl apply -f jenkins-config.yaml
kubectl apply -f jenkins-deploy.yaml
kubectl rollout status deployment/jenkins-c -n utilities --timeout=300s
```

### Step 2: Retrieve JNLP secret, then deploy agent

```bash
# Get node IP
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

# Open Jenkins UI and navigate to:
# Manage Jenkins → Nodes → android-agent → (status page shows the secret)
echo "Open: http://${NODE_IP}:30881/computer/android-agent/"

# Once you have the secret, update agent-secret.yaml, then:
kubectl apply -f agent-secret.yaml
kubectl apply -f jenkins-agent-deploy.yaml
```

## Access

- **Jenkins UI:** `http://<node-ip>:30881`
- **Default credentials:** admin / admin123 (set in 01-security.groovy)

## Gradle Cache

The agent mounts a 5Gi PVC at `/root/.gradle`. After the first build downloads all
Gradle dependencies, subsequent builds reuse the cache and run significantly faster.
The cache persists even if the agent pod restarts.

## Rotating the JNLP Secret

If you delete and recreate the Jenkins controller PVC (resetting Jenkins state), a new
JNLP secret is generated for the android-agent node. To update:

```bash
# Get new secret from Jenkins UI → Nodes → android-agent
kubectl create secret generic jenkins-agent-secret \
  --from-literal=JENKINS_SECRET=<NEW_SECRET> \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment/android-build-agent -n utilities
```

## Key Differences vs Option B

| | Option C (this) | Option B |
|---|---|---|
| Agent lifecycle | Always running | Per-build pod |
| Gradle cache | Persistent PVC (fast after 1st build) | emptyDir (slow every build) |
| RBAC required | No | Yes |
| Agent startup | Instant | ~30s pod spin-up per build |
| Build isolation | Shared workspace (use cleanWs()) | Complete isolation |
```

- [ ] **Step 2: Commit**

```bash
git add jenkins-k8s/option-c-static-agent/README.md
git commit -m "docs: add Option C deployment README"
```

---

## Phase 6 — Shared Pipeline

### Task 17: Write the shared Jenkinsfile

**Files:**
- Create: `jenkins-k8s/shared/Jenkinsfile`

- [ ] **Step 1: Write `jenkins-k8s/shared/Jenkinsfile`**

```groovy
pipeline {
    agent { label 'android-agent' }

    environment {
        ANDROID_HOME  = '/opt/android-sdk'
        SONAR_TOKEN   = credentials('sonar-token')
        NEXUS_CREDS   = credentials('nexus-creds')
        FAILED_STAGE  = ''
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/android/sunflower.git', branch: 'main'
                script {
                    env.VERSION_NAME = sh(
                        script: "git describe --tags --always 2>/dev/null || git rev-parse --short HEAD",
                        returnStdout: true
                    ).trim()
                    env.APK_FILENAME = "app-debug-${env.VERSION_NAME}.apk"
                    echo "Building version: ${env.VERSION_NAME}"
                }
            }
            post { failure { script { env.FAILED_STAGE = 'Checkout' } } }
        }

        stage('Validate Environment') {
            steps {
                sh '''
                    set -e
                    echo "=== Environment Validation ==="
                    echo "ANDROID_HOME: $ANDROID_HOME"
                    [ -d "$ANDROID_HOME" ] || { echo "ANDROID_HOME not found at $ANDROID_HOME"; exit 1; }
                    java -version 2>&1
                    ./gradlew --version
                    curl --version | head -1
                    semgrep --version
                    dependency-check.sh --version
                    jq --version
                    echo "=== All checks passed ==="
                '''
            }
            post { failure { script { env.FAILED_STAGE = 'Validate Environment' } } }
        }

        stage('Build Debug APK') {
            steps {
                sh """
                    # Inject build version
                    sed -i "s/versionCode [0-9]*/versionCode ${env.BUILD_NUMBER}/" app/build.gradle || true
                    sed -i 's/versionName "[^"]*"/versionName "${env.VERSION_NAME}"/' app/build.gradle || true
                    ./gradlew assembleDebug -PjacocoEnabled=true --stacktrace
                """
            }
            post { failure { script { env.FAILED_STAGE = 'Build Debug APK' } } }
        }

        stage('Unit Tests') {
            steps {
                sh './gradlew test --stacktrace'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                }
                failure { script { env.FAILED_STAGE = 'Unit Tests' } }
            }
        }

        stage('SAST') {
            parallel {
                stage('Android Lint') {
                    steps { sh './gradlew lint || true' }
                }
                stage('SpotBugs') {
                    steps { sh './gradlew spotbugsMain || true' }
                }
                stage('Semgrep') {
                    steps {
                        sh '''
                            mkdir -p reports
                            semgrep --config=auto \
                                    --json \
                                    --output=reports/semgrep-report.json \
                                    --exclude="build/" \
                                    . 2>/dev/null || true
                            python3 /opt/semgrep-to-html.py \
                                reports/semgrep-report.json \
                                reports/semgrep-report.html || true
                        '''
                    }
                }
            }
            post {
                always {
                    recordIssues(
                        enabledForFailure: true,
                        tools: [
                            spotBugs(pattern: '**/build/reports/spotbugs/*.xml'),
                            androidLintParser(pattern: '**/build/reports/lint-results*.xml')
                        ]
                    )
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports', reportFiles: 'semgrep-report.html',
                        reportName: 'Semgrep SAST Report'
                    ])
                }
                failure { script { env.FAILED_STAGE = 'SAST' } }
            }
        }

        stage('SCA / OSA') {
            steps {
                sh '''
                    mkdir -p reports/dependency-check
                    dependency-check.sh \
                        --project "sunflower" \
                        --scan . \
                        --exclude "**/.git/**" \
                        --format HTML --format JSON --format XML \
                        --out reports/dependency-check/ \
                        --failOnCVSS 9 || true
                '''
            }
            post {
                always {
                    dependencyCheckPublisher(
                        pattern: 'reports/dependency-check/dependency-check-report.xml',
                        failedTotalCritical: 1,
                        unstableTotalHigh: 5
                    )
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports/dependency-check',
                        reportFiles: 'dependency-check-report.html',
                        reportName: 'OWASP Dependency Check'
                    ])
                }
                failure { script { env.FAILED_STAGE = 'SCA / OSA' } }
            }
        }

        stage('License Compliance') {
            steps {
                sh '''
                    ./gradlew generateLicenseReport || true
                    mkdir -p reports
                    /opt/check-licenses.sh build/reports/licenses/ reports/license-report.html
                '''
            }
            post {
                always {
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports', reportFiles: 'license-report.html',
                        reportName: 'License Compliance'
                    ])
                }
                failure { script { env.FAILED_STAGE = 'License Compliance' } }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        ./gradlew sonarqube \
                            -Dsonar.projectKey=sunflower-android \
                            -Dsonar.projectName="Sunflower Android" \
                            -Dsonar.coverage.jacoco.xmlReportPaths=app/build/reports/jacoco/testDebugUnitTestCoverage/testDebugUnitTestCoverage.xml \
                            -Dsonar.java.binaries=app/build/intermediates/javac
                    """
                }
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
            post { failure { script { env.FAILED_STAGE = 'SonarQube Analysis' } } }
        }

        stage('Archive + Nexus Upload') {
            steps {
                script {
                    def apkPath = sh(
                        script: "find app/build/outputs/apk/debug -name '*.apk' | head -1",
                        returnStdout: true
                    ).trim()
                    sh """
                        cp "${apkPath}" "${env.APK_FILENAME}"
                        curl -sf \
                            -u "${env.NEXUS_CREDS_USR}:${env.NEXUS_CREDS_PSW}" \
                            --upload-file "${env.APK_FILENAME}" \
                            "http://nexus.utilities.svc.cluster.local:8081/repository/android-releases/${env.APK_FILENAME}"
                        echo "Uploaded to Nexus: ${env.APK_FILENAME}"
                    """
                }
                archiveArtifacts artifacts: "${env.APK_FILENAME}", fingerprint: true
            }
            post { failure { script { env.FAILED_STAGE = 'Archive + Nexus Upload' } } }
        }
    }

    post {
        failure {
            script {
                def logLines   = currentBuild.rawBuild.getLog(200)
                def logTail    = logLines.join('\n').replace("'", "\\'")
                def errorLines = logLines.takeRight(20).join('\n').replace("'", "\\'")
                def failedAt   = env.FAILED_STAGE ?: 'Unknown'

                sh """
                    FAILED_STAGE='${failedAt}' \
                    ERROR_SNIPPET='${errorLines}' \
                    LOG_TAIL='${logTail}' \
                    BUILD_NUMBER='${env.BUILD_NUMBER}' \
                    JOB_NAME='${env.JOB_NAME}' \
                    /opt/llm-analysis.sh
                """

                sh """
                    mkdir -p build-logs
                    cp llm-analysis.md build-logs/ 2>/dev/null || true
                    find . -path '*/test-results/*.xml' | head -50 | while read f; do
                        cp --parents "\$f" build-logs/
                    done
                    find reports -name '*.html' 2>/dev/null | while read f; do
                        cp --parents "\$f" build-logs/
                    done
                    zip -r build-${env.BUILD_NUMBER}-logs.zip build-logs/ 2>/dev/null || true
                """
            }
            emailext(
                subject: "[JENKINS FAILURE] \${env.JOB_NAME} #\${env.BUILD_NUMBER} — Failed at: \${env.FAILED_STAGE ?: 'Unknown'}",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="failure-email.groovy"}',
                attachmentsPattern: "build-\${env.BUILD_NUMBER}-logs.zip"
            )
        }

        success {
            script {
                sh """
                    mkdir -p build-artifacts
                    cp '${env.APK_FILENAME}' build-artifacts/ 2>/dev/null || true
                    find . -path '*/test-results/*.xml' | head -50 | while read f; do
                        cp --parents "\$f" build-artifacts/
                    done
                    cp reports/semgrep-report.html build-artifacts/ 2>/dev/null || true
                    cp reports/dependency-check/dependency-check-report.html build-artifacts/ 2>/dev/null || true
                    cp reports/license-report.html build-artifacts/ 2>/dev/null || true
                    zip -r build-${env.BUILD_NUMBER}-artifacts.zip build-artifacts/ 2>/dev/null || true
                """
            }
            emailext(
                subject: "[JENKINS SUCCESS] \${env.JOB_NAME} #\${env.BUILD_NUMBER} — All stages passed",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="success-email.groovy"}',
                attachmentsPattern: "build-\${env.BUILD_NUMBER}-artifacts.zip"
            )
        }

        cleanup {
            cleanWs()
        }
    }
}
```

- [ ] **Step 2: Validate Jenkinsfile syntax via Jenkins CLI**

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

# Download Jenkins CLI jar
curl -sO "http://${NODE_IP}:30880/jnlpJars/jenkins-cli.jar"

# Validate pipeline syntax (requires Jenkins to be running)
java -jar jenkins-cli.jar \
  -s "http://${NODE_IP}:30880" \
  -auth admin:admin123 \
  declarative-linter < jenkins-k8s/shared/Jenkinsfile
```

Expected: `Jenkinsfile successfully validated.`

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/shared/Jenkinsfile
git commit -m "feat: add shared Android CI/CD Jenkinsfile with 9 stages + LLM post blocks"
```

---

## Phase 7 — Email Templates

### Task 18: Write failure email template

**Files:**
- Create: `jenkins-k8s/shared/failure-email.groovy`

- [ ] **Step 1: Write `jenkins-k8s/shared/failure-email.groovy`**

```groovy
// Jenkins email-ext Groovy template — failure notification
import java.text.SimpleDateFormat

def build    = binding.getVariable("build")
def jobName  = build.project.name
def buildNum = build.number
def duration = build.durationString.replace(' and counting', '')
def sdf      = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
def timestamp = sdf.format(new Date(build.startTimeInMillis))

def failedStage = ""
try { failedStage = build.getEnvironment()['FAILED_STAGE'] ?: "Unknown" } catch (e) { failedStage = "Unknown" }

def llmAnalysisHtml = ""
try {
    def ws = build.workspace
    if (ws) {
        def f = ws.child("llm-analysis.md")
        if (f.exists()) {
            def md = f.readToString()
            // Basic Markdown → HTML conversion
            md = md.replaceAll(/(?m)^# (.+)$/, '<h1 style="color:#333">$1</h1>')
            md = md.replaceAll(/(?m)^## (.+)$/, '<h2 style="color:#1976d2;border-left:4px solid #1976d2;padding-left:8px">$1</h2>')
            md = md.replaceAll(/(?m)^### (.+)$/, '<h3 style="color:#555">$1</h3>')
            md = md.replaceAll(/(?m)^\| (.+) \|$/, '<tr><td style="border:1px solid #ddd;padding:6px">$1</td></tr>'.replaceAll('\\|', '</td><td style="border:1px solid #ddd;padding:6px">'))
            md = md.replaceAll(/```mermaid\n([\s\S]*?)```/, '<pre style="background:#f4f4f4;padding:12px;border-radius:4px;font-size:11px;font-style:italic">[Mermaid Diagram — open llm-analysis.md to render]\n$1</pre>')
            md = md.replaceAll(/```[a-z]*\n([\s\S]*?)```/, '<pre style="background:#2d2d2d;color:#f8f8f2;padding:12px;border-radius:4px;overflow-x:auto;font-size:12px">$1</pre>')
            md = md.replaceAll(/`([^`]+)`/, '<code style="background:#e8e8e8;padding:2px 4px;border-radius:3px;font-size:12px">$1</code>')
            md = md.replaceAll(/\*\*([^*]+)\*\*/, '<strong>$1</strong>')
            md = md.replaceAll(/(?m)^---$/, '<hr style="border:none;border-top:1px solid #eee;margin:16px 0"/>')
            md = md.replaceAll(/\n\n/, '</p><p style="margin:8px 0">')
            llmAnalysisHtml = "<p style='margin:8px 0'>${md}</p>"
        }
    }
} catch (e) {
    llmAnalysisHtml = "<p><em>LLM analysis not available: ${e.message}</em></p>"
}

return """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"/></head>
<body style="margin:0;padding:0;background:#f5f5f5;font-family:Arial,sans-serif;">
<div style="max-width:900px;margin:20px auto;background:white;border-radius:8px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,0.1);">

  <!-- Header -->
  <div style="background:#c62828;color:white;padding:24px 32px;">
    <div style="font-size:22px;font-weight:bold;">Build Failed</div>
    <div style="margin-top:6px;opacity:0.9;font-size:14px;">${jobName} #${buildNum} — Failed at stage: <strong>${failedStage}</strong></div>
  </div>

  <!-- Build Details -->
  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #c62828;padding-left:12px;">Build Details</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;width:140px;font-size:13px;">Job</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${jobName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Build #</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${buildNum}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Failed Stage</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;color:#c62828;font-weight:bold;">${failedStage}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Duration</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${duration}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Timestamp</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${timestamp}</td></tr>
    </table>
  </div>

  <!-- LLM Analysis -->
  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 8px;font-size:15px;color:#333;border-left:4px solid #1976d2;padding-left:12px;">LLM Failure Analysis</h2>
    <p style="margin:0 0 16px;font-size:12px;color:#666;">Analyzed by Qwen2.5-Coder (Ollama), DeepSeek-Coder-V2-Lite (llama.cpp), and CodeLlama (if available). Open the attached <strong>llm-analysis.md</strong> for rendered Mermaid diagrams.</p>
    <div style="font-size:13px;line-height:1.7;">
      ${llmAnalysisHtml}
    </div>
  </div>

  <!-- Footer -->
  <div style="padding:14px 32px;background:#f9f9f9;font-size:11px;color:#888;">
    Jenkins CI/CD &nbsp;|&nbsp; utilities namespace &nbsp;|&nbsp; Build logs and LLM analysis attached as zip
  </div>
</div>
</body>
</html>"""
```

- [ ] **Step 2: Create the email templates ConfigMap and mount it in both Jenkins deployments**

The email-ext plugin reads Groovy templates from `$JENKINS_HOME/email-templates/`.
Both templates are a few KB — well within the 1MB ConfigMap limit.

```bash
# Create ConfigMap from the two template files
kubectl create configmap jenkins-email-templates \
  --from-file=failure-email.groovy=jenkins-k8s/shared/failure-email.groovy \
  --from-file=success-email.groovy=jenkins-k8s/shared/success-email.groovy \
  -n utilities \
  --dry-run=client -o yaml > jenkins-k8s/shared/email-templates-configmap.yaml

kubectl apply -f jenkins-k8s/shared/email-templates-configmap.yaml
```

Then add the following to **both** `jenkins-b` and `jenkins-c` Deployment specs
(in `volumes` and `volumeMounts` of the `jenkins` container):

```yaml
# Under spec.template.spec.volumes:
      - name: email-templates
        configMap:
          name: jenkins-email-templates

# Under spec.template.spec.containers[jenkins].volumeMounts:
        - name: email-templates
          mountPath: /var/jenkins_home/email-templates
```

Apply the updated Deployments:

```bash
kubectl rollout restart deployment/jenkins-b -n utilities
kubectl rollout restart deployment/jenkins-c -n utilities
```

Verify templates are visible inside Jenkins:

```bash
kubectl exec -n utilities deploy/jenkins-b -- \
  ls /var/jenkins_home/email-templates/
```

Expected: `failure-email.groovy  success-email.groovy`

- [ ] **Step 3: Commit**

```bash
git add jenkins-k8s/shared/failure-email.groovy
git commit -m "feat: add HTML failure email template with inline LLM analysis"
```

---

### Task 19: Write success email template

**Files:**
- Create: `jenkins-k8s/shared/success-email.groovy`

- [ ] **Step 1: Write `jenkins-k8s/shared/success-email.groovy`**

```groovy
// Jenkins email-ext Groovy template — success notification
import java.text.SimpleDateFormat

def build    = binding.getVariable("build")
def jobName  = build.project.name
def buildNum = build.number
def duration = build.durationString.replace(' and counting', '')
def sdf      = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
def timestamp = sdf.format(new Date(build.startTimeInMillis))

def versionName = ""
try { versionName = build.getEnvironment()['VERSION_NAME'] ?: "unknown" } catch (e) { versionName = "unknown" }
def apkFilename = "app-debug-${versionName}.apk"
def nexusUrl = "http://nexus.utilities.svc.cluster.local:8081/repository/android-releases/${apkFilename}"

return """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"/></head>
<body style="margin:0;padding:0;background:#f5f5f5;font-family:Arial,sans-serif;">
<div style="max-width:900px;margin:20px auto;background:white;border-radius:8px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,0.1);">

  <!-- Header -->
  <div style="background:#2e7d32;color:white;padding:24px 32px;">
    <div style="font-size:22px;font-weight:bold;">Build Succeeded</div>
    <div style="margin-top:6px;opacity:0.9;font-size:14px;">${jobName} #${buildNum} — All stages passed</div>
  </div>

  <!-- Build Details -->
  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #2e7d32;padding-left:12px;">Build Details</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;width:140px;font-size:13px;">Job</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${jobName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Build #</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${buildNum}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Version</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;font-family:monospace;">${versionName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Duration</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${duration}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Timestamp</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${timestamp}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">APK</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;"><a href="${nexusUrl}" style="color:#0066cc;">${apkFilename}</a></td></tr>
    </table>
  </div>

  <!-- Pipeline Stages Summary -->
  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #2e7d32;padding-left:12px;">Pipeline Stages</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr style="background:#4a4a4a;color:white;"><th style="padding:8px 12px;text-align:left;font-size:13px;">Stage</th><th style="padding:8px 12px;text-align:left;font-size:13px;">Status</th></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Checkout</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Validate Environment</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Build Debug APK</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Unit Tests</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SAST (Lint + SpotBugs + Semgrep)</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SCA / OSA (OWASP Dependency-Check)</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">License Compliance</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SonarQube Analysis</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Archive + Nexus Upload</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
    </table>
  </div>

  <!-- Security Summary -->
  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #2e7d32;padding-left:12px;">Security Summary</h2>
    <p style="font-size:12px;color:#666;margin:0 0 12px;">See attached HTML reports for full details.</p>
    <table style="border-collapse:collapse;width:100%;">
      <tr style="background:#4a4a4a;color:white;"><th style="padding:8px 12px;font-size:13px;text-align:left;">Check</th><th style="padding:8px 12px;font-size:13px;text-align:left;">Result</th></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SAST (Lint + SpotBugs + Semgrep)</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">See semgrep-report.html</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SCA / OSA (OWASP DC)</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">See dependency-check-report.html</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">License Compliance</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">See license-report.html</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SonarQube Quality Gate</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;color:#2e7d32;font-weight:bold;">PASSED</td></tr>
    </table>
  </div>

  <!-- Footer -->
  <div style="padding:14px 32px;background:#f9f9f9;font-size:11px;color:#888;">
    Jenkins CI/CD &nbsp;|&nbsp; utilities namespace &nbsp;|&nbsp; APK and security reports attached as zip
  </div>
</div>
</body>
</html>"""
```

- [ ] **Step 2: Commit**

```bash
git add jenkins-k8s/shared/success-email.groovy
git commit -m "feat: add HTML success email template with security summary"
```

---

## Phase 8 — Create Jenkins Pipeline Job

### Task 20: Create pipeline job via Jenkins CLI (both options)

> Run this task for whichever Jenkins instance you are testing first.
> Repeat for the second instance substituting port 30881 and admin credentials.

- [ ] **Step 1: Create the pipeline job XML**

```bash
cat > /tmp/android-build-job.xml <<'XML'
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>Android build pipeline with security scanning and LLM failure analysis</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <!--
            Option B (ephemeral pods): use a network-accessible git URL.
            The ephemeral agent pod cannot access the host filesystem, so
            file:// URLs won't work. Push to a git remote (GitHub, Gitea, etc.)
            and use that URL here.

            Option C (static agent with host-mounted path): if the agent pod
            mounts the host filesystem at the same path, file:// works.
            Otherwise use a network URL as well.
          -->
          <url>file:///home/dk/Documents/git/testing-grounds</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/master</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
    </scm>
    <scriptPath>jenkins-k8s/shared/Jenkinsfile</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>
XML
```

- [ ] **Step 2: Submit the job to Jenkins (Option B — port 30880)**

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
curl -sO "http://${NODE_IP}:30880/jnlpJars/jenkins-cli.jar"

java -jar jenkins-cli.jar \
  -s "http://${NODE_IP}:30880" \
  -auth admin:admin123 \
  create-job android-build < /tmp/android-build-job.xml

echo "Job created. Verify at: http://${NODE_IP}:30880/job/android-build/"
```

- [ ] **Step 3: Submit the job to Jenkins (Option C — port 30881)**

```bash
java -jar jenkins-cli.jar \
  -s "http://${NODE_IP}:30881" \
  -auth admin:admin123 \
  create-job android-build < /tmp/android-build-job.xml
```

- [ ] **Step 4: Trigger a build and watch it**

```bash
# Trigger build on Option B
java -jar jenkins-cli.jar \
  -s "http://${NODE_IP}:30880" \
  -auth admin:admin123 \
  build android-build -s -v
```

`-s` waits for completion, `-v` streams console output.

- [ ] **Step 5: Commit**

```bash
git commit --allow-empty -m "docs: pipeline job created in Jenkins via CLI"
```

---

## Phase 9 — Validation

### Task 21: Verify full success path

- [ ] **Step 1: Run a successful build and verify all stages pass**

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
java -jar jenkins-cli.jar -s "http://${NODE_IP}:30880" -auth admin:admin123 build android-build -s -v
```

Expected: All 9 stages green.

- [ ] **Step 2: Verify APK uploaded to Nexus**

```bash
curl -s "http://${NODE_IP}:30081/service/rest/v1/search/assets?repository=android-releases" \
  | jq -r '.items[].path'
```

Expected: At least one `app-debug-*.apk` listed.

- [ ] **Step 3: Verify SonarQube received the analysis**

```bash
curl -su admin:admin "http://${NODE_IP}:30900/api/projects/search?projects=sunflower-android" \
  | jq '.components[0].name'
```

Expected: `"Sunflower Android"`

- [ ] **Step 4: Verify success email received**

Check inbox at `hello.dk@outlook.com`. Expected:
- Subject: `[JENKINS SUCCESS] android-build #1 — All stages passed`
- Attachment: `build-1-artifacts.zip` containing APK and HTML reports

---

### Task 22: Verify failure path (LLM analysis + failure email)

- [ ] **Step 1: Inject a deliberate build failure**

Edit the Jenkinsfile temporarily to fail the build at stage 3:

```groovy
stage('Build Debug APK') {
    steps {
        sh 'echo "Deliberate failure for testing"; exit 1'
    }
    ...
}
```

- [ ] **Step 2: Trigger the build**

```bash
java -jar jenkins-cli.jar -s "http://${NODE_IP}:30880" -auth admin:admin123 build android-build -s -v
```

Expected: Build fails at `Build Debug APK`.

- [ ] **Step 3: Verify LLM analysis ran**

```bash
# Find the workspace (for Option C; for Option B the pod is gone after build)
kubectl exec -n utilities deploy/android-build-agent -- cat /home/jenkins/agent/llm-analysis.md | head -40
```

Expected: Markdown file with 3 LLM analysis sections.

- [ ] **Step 4: Verify failure email received**

Check inbox at `hello.dk@outlook.com`. Expected:
- Subject: `[JENKINS FAILURE] android-build #2 — Failed at: Build Debug APK`
- Attachment: `build-2-logs.zip` containing `llm-analysis.md`, `console.log`, test XMLs

- [ ] **Step 5: Revert the deliberate failure**

```groovy
// Restore original Build Debug APK stage content
stage('Build Debug APK') {
    steps {
        sh """
            sed -i "s/versionCode [0-9]*/versionCode ${env.BUILD_NUMBER}/" app/build.gradle || true
            ./gradlew assembleDebug -PjacocoEnabled=true --stacktrace
        """
    }
    ...
}
```

- [ ] **Step 6: Final commit**

```bash
git add jenkins-k8s/
git commit -m "feat: complete Jenkins LLM-assisted Android CI/CD pipeline — both options deployed and validated"
```

---

## Quick Reference

### Service DNS (in-cluster)

| Service | DNS | Port |
|---|---|---|
| SMTP relay | `smtp.utilities.svc.cluster.local` | 25 |
| SonarQube | `sonarqube.utilities.svc.cluster.local` | 9000 |
| PostgreSQL | `postgres.utilities.svc.cluster.local` | 5432 |
| Nexus | `nexus.utilities.svc.cluster.local` | 8081 |
| Jenkins B | `jenkins-b.utilities.svc.cluster.local` | 8080 |
| Jenkins C | `jenkins-c.utilities.svc.cluster.local` | 8080 / 50000 |

### NodePort access

| Service | NodePort |
|---|---|
| Jenkins B UI | 30880 |
| Jenkins C UI | 30881 |
| Jenkins C JNLP | 30500 |
| SonarQube UI | 30900 |
| Nexus UI | 30081 |

### LLM endpoints

| Endpoint | Type | Model |
|---|---|---|
| `192.168.1.10:11434` | Ollama | `Qwen2.5-Coder:14B-Instruct` (primary), `codellama` (optional) |
| `192.168.1.24:21434` | llama.cpp | `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` |

### Pull CodeLlama when ready

```bash
curl -X POST http://192.168.1.10:11434/api/pull \
  -H "Content-Type: application/json" \
  -d '{"name": "codellama:13b"}'
```
