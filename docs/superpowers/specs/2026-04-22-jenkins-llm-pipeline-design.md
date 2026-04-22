# Jenkins LLM-Assisted Android CI/CD Pipeline — Design Spec

**Date:** 2026-04-22
**Status:** Approved
**Namespace:** `utilities`
**Email notifications:** hello.dk@outlook.com

---

## Table of Contents

1. [Overview](#overview)
2. [Repository Layout](#repository-layout)
3. [Infrastructure Components](#infrastructure-components)
4. [Option B — Ephemeral K8s Agents](#option-b--ephemeral-k8s-agents)
5. [Option C — Static JNLP Agent](#option-c--static-jnlp-agent)
6. [Pipeline Stages](#pipeline-stages)
7. [Security Toolchain](#security-toolchain)
8. [SonarQube Integration](#sonarqube-integration)
9. [Build Versioning and Nexus Upload](#build-versioning-and-nexus-upload)
10. [LLM Failure Analysis](#llm-failure-analysis)
11. [Email Notifications](#email-notifications)
12. [Jenkins UI Reports](#jenkins-ui-reports)
13. [LLM Endpoints Reference](#llm-endpoints-reference)

---

## Overview

This spec describes a fully self-hosted Jenkins CI/CD system deployed on Kubernetes in the `utilities` namespace. It builds a sample Android application through a multi-stage security-hardened pipeline. On failure, it performs automated root-cause analysis using two local LLM endpoints. Email notifications are sent on both success and failure to `hello.dk@outlook.com`.

Two deployment architectures are provided as reference implementations:

| | Option B | Option C |
|---|---|---|
| Agent model | Ephemeral pods (Kubernetes plugin) | Static persistent JNLP agent |
| Agent lifecycle | Created per build, destroyed after | Always running |
| Complexity | Higher (RBAC, JCasC) | Lower |
| Best for | Scale, isolation, production | Simplicity, fast startup |

Both share the same Jenkinsfile, shared scripts, and infrastructure services.

---

## Repository Layout

```
jenkins-k8s/
├── namespace.yaml
│
├── shared/
│   ├── postfix-deploy.yaml           # SMTP relay (shared by both options)
│   ├── sonarqube-deploy.yaml         # SonarQube Community Edition
│   ├── postgres-deploy.yaml          # PostgreSQL for SonarQube
│   ├── nexus-deploy.yaml             # Nexus Repository OSS
│   ├── pvcs.yaml                     # All PersistentVolumeClaims
│   ├── llm-analysis.sh               # LLM API call script (Ollama + llama.cpp)
│   └── Jenkinsfile                   # Shared pipeline (both options)
│
├── option-b-ephemeral-agents/
│   ├── README.md                     # Setup guide for Option B
│   ├── rbac.yaml                     # ServiceAccount + ClusterRole + Binding
│   ├── jenkins-deploy.yaml           # Controller Deployment + Service
│   └── jenkins-casc-config.yaml      # ConfigMap: JCasC (K8s cloud + pod template)
│
└── option-c-static-agent/
    ├── README.md                     # Setup guide for Option C
    ├── jenkins-deploy.yaml           # Controller Deployment + Service
    ├── jenkins-agent-deploy.yaml     # Android JNLP agent Deployment
    ├── agent-secret.yaml             # JNLP connection secret
    └── jenkins-config.yaml           # ConfigMap: node pre-seed config
```

---

## Infrastructure Components

All components deploy into the `utilities` namespace and are reachable via cluster-internal DNS (`*.utilities.svc.cluster.local`).

### Postfix SMTP Relay

| Field | Value |
|---|---|
| Image | `namshi/smtp` |
| Service DNS | `smtp.utilities.svc.cluster.local:25` |
| Purpose | Outbound mail relay for Jenkins email-ext plugin |
| Config | Allowed networks set to pod CIDR; no auth required in-cluster |

### SonarQube Community Edition

| Field | Value |
|---|---|
| Image | `sonarqube:community` |
| Service DNS | `sonarqube.utilities.svc.cluster.local:9000` |
| Database | PostgreSQL (`postgres.utilities.svc.cluster.local:5432`) |
| PVC | `sonarqube-data` (10Gi), `sonarqube-extensions` (2Gi) |
| Purpose | Code coverage, quality gates, security hotspots, code smells |
| Jenkins plugin | SonarQube Scanner for Jenkins |

### PostgreSQL (SonarQube backend)

| Field | Value |
|---|---|
| Image | `postgres:15` |
| Service DNS | `postgres.utilities.svc.cluster.local:5432` |
| PVC | `postgres-data` (5Gi) |
| Credentials | Stored in Kubernetes Secret `sonarqube-db-secret` |

### Nexus Repository OSS

| Field | Value |
|---|---|
| Image | `sonatype/nexus3` |
| Service DNS | `nexus.utilities.svc.cluster.local:8081` |
| PVC | `nexus-data` (20Gi) |
| Repository | `android-releases` (raw hosted) |
| Purpose | Versioned APK artifact storage, Firebase App Distribution equivalent |
| Upload API | `PUT /repository/android-releases/<filename>` (basic auth) |

---

## Option B — Ephemeral K8s Agents

### Architecture

```
┌─────────────────────────────────────────────────────┐
│ utilities namespace                                  │
│                                                      │
│  ┌──────────────────┐      ┌─────────────────────┐  │
│  │ Jenkins Controller│─────▶│ Kubernetes API      │  │
│  │ (jenkins/jenkins: │      │ (in-cluster)        │  │
│  │  lts)             │      └──────────┬──────────┘  │
│  │ Port: 8080        │                 │              │
│  │ PVC: 10Gi         │                 │ creates pod  │
│  └──────────────────┘                 ▼              │
│                              ┌─────────────────────┐ │
│                              │ Android Agent Pod   │ │
│                              │ (thyrlian/android-  │ │
│                              │  sdk:latest)        │ │
│                              │ Lifecycle: per-build│ │
│                              └─────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### RBAC

```yaml
# ServiceAccount: jenkins (utilities ns)
# ClusterRole: jenkins-agent-role
#   Rules: pods (get/list/create/delete/watch)
#          pods/exec, pods/log
#          secrets (get)
# ClusterRoleBinding: jenkins → jenkins-agent-role
```

### JCasC Configuration (ConfigMap)

The Jenkins Configuration as Code (JCasC) plugin pre-configures:
- Kubernetes cloud pointing to in-cluster API (`https://kubernetes.default.svc`)
- Android agent pod template:
  - Label: `android-agent`
  - Container: `thyrlian/android-sdk:latest`
  - Mounts: Gradle cache (emptyDir), Android SDK cache (emptyDir)
  - Resources: requests 1 CPU / 2Gi RAM, limits 2 CPU / 4Gi RAM
- SMTP server: `smtp.utilities.svc.cluster.local`
- SonarQube server URL: `http://sonarqube.utilities.svc.cluster.local:9000`

### Deployment Steps

```bash
# 1. Apply namespace
kubectl apply -f namespace.yaml

# 2. Apply shared infrastructure
kubectl apply -f shared/

# 3. Apply Option B manifests
kubectl apply -f option-b-ephemeral-agents/

# 4. Wait for Jenkins to be ready
kubectl rollout status deployment/jenkins-b -n utilities

# 5. Get initial admin password
kubectl exec -n utilities deploy/jenkins-b -- \
  cat /var/jenkins_home/secrets/initialAdminPassword
```

---

## Option C — Static JNLP Agent

### Architecture

```
┌─────────────────────────────────────────────────────┐
│ utilities namespace                                  │
│                                                      │
│  ┌──────────────────┐  JNLP  ┌─────────────────────┐│
│  │ Jenkins Controller│◀──────│ Android Build Agent  ││
│  │ (jenkins/jenkins: │ :50000 │ (thyrlian/android-  ││
│  │  lts)             │        │  sdk + jnlp-agent)  ││
│  │ Port: 8080 + 50000│        │ Persistent pod       ││
│  │ PVC: 10Gi         │        │ PVC: gradle-cache 5G ││
│  └──────────────────┘        └─────────────────────┘│
└─────────────────────────────────────────────────────┘
```

### Agent Connection

The agent Deployment uses these environment variables from the `agent-secret` Secret:

| Env Var | Value |
|---|---|
| `JENKINS_URL` | `http://jenkins-c.utilities.svc.cluster.local:8080` |
| `JENKINS_AGENT_NAME` | `android-agent` |
| `JENKINS_SECRET` | JNLP secret token (generated by Jenkins) |
| `JENKINS_TUNNEL` | `jenkins-c.utilities.svc.cluster.local:50000` |

> **Note:** After first Jenkins boot, retrieve the agent secret from Jenkins UI under
> `Manage Jenkins → Nodes → android-agent → secret` and update `agent-secret.yaml`.

### Gradle Cache PVC

Option C uses a persistent `gradle-cache` PVC (5Gi) mounted at `/root/.gradle` in the agent pod. This persists the Gradle dependency cache between builds, significantly reducing build times after the first run.

### Deployment Steps

```bash
# 1. Apply namespace
kubectl apply -f namespace.yaml

# 2. Apply shared infrastructure
kubectl apply -f shared/

# 3. Apply controller only first (need JNLP secret)
kubectl apply -f option-c-static-agent/jenkins-deploy.yaml
kubectl apply -f option-c-static-agent/jenkins-config.yaml

# 4. Get JNLP secret from Jenkins UI, then update agent-secret.yaml
# Manage Jenkins → Nodes → android-agent → configure → secret

# 5. Apply agent with secret
kubectl apply -f option-c-static-agent/agent-secret.yaml
kubectl apply -f option-c-static-agent/jenkins-agent-deploy.yaml
```

---

## Pipeline Stages

Both options use the same `shared/Jenkinsfile`. The only difference is the `agent { label }` declaration:
- Option B: `label 'android-agent'` (matches K8s pod template)
- Option C: `label 'android-agent'` (matches static node name)

### Stage Overview

```
Stage 1:  Checkout
Stage 2:  Validate Environment
Stage 3:  Build Debug APK
Stage 4:  Unit Tests
Stage 5:  SAST
Stage 6:  SCA / OSA
Stage 7:  License Compliance
Stage 8:  SonarQube Analysis
Stage 9:  Archive + Nexus Upload

// Implemented as Jenkins `post` blocks, not pipeline stages:
post failure:  LLM Analysis       → runs only when any stage fails
post always:   Email Notification → runs on every build result
```

> **Note:** Stages 1–9 are declared inside `stages { }` in the Jenkinsfile.
> LLM analysis and email are `post { failure {} }` and `post { always {} }` blocks respectively.
> This means a failed stage does not block the email — `post` blocks always run.

### Stage 1: Checkout

```groovy
git url: 'https://github.com/android/sunflower.git', branch: 'main'
```

Clones the official Android Sunflower sample app as the build target.

### Stage 2: Validate Environment

Asserts presence of:
- `$ANDROID_HOME` (Android SDK root)
- Java 17+
- Gradle wrapper (`./gradlew`)
- `curl` (required for LLM API calls)

### Stage 3: Build Debug APK

```bash
./gradlew assembleDebug \
  -PjacocoEnabled=true \   # enables JaCoCo coverage instrumentation
  --stacktrace
```

Sets `versionCode = ${BUILD_NUMBER}` and `versionName` via `git describe --tags --always`.

### Stage 4: Unit Tests

```bash
./gradlew test --stacktrace
```

Produces JUnit XML reports at `**/build/test-results/`.

### Stage 5: SAST

Three tools run in parallel sub-steps:

| Tool | Command | Output |
|---|---|---|
| Android Lint | `./gradlew lint` | `lint-results.html` |
| SpotBugs + FindSecBugs | `./gradlew spotbugsMain` | `spotbugs-report.html` |
| Semgrep OSS | `semgrep --config=auto --json` | `semgrep-report.json` → converted to HTML |

Results published via **Warnings Next Generation Plugin** in Jenkins UI.

### Stage 6: SCA / OSA

```bash
dependency-check.sh \
  --project "sunflower" \
  --scan . \
  --format HTML --format JSON --format XML \
  --out reports/dependency-check/
```

Scans all Gradle dependencies and transitive dependencies against the NVD CVE database.
Fails the build if any **Critical** CVEs are found (configurable threshold).

Published via **OWASP Dependency-Check Jenkins Plugin**.

### Stage 7: License Compliance

```bash
./gradlew generateLicenseReport
```

Generates a per-dependency license inventory. Post-processing script classifies each:

| Classification | Licenses | Action |
|---|---|---|
| Allowed (green) | MIT, Apache-2.0, BSD-2/3, ISC | Pass |
| Review (amber) | LGPL, MPL, CDDL | Warn, continue |
| Blocked (red) | AGPL, GPL-2.0, GPL-3.0, unknown | Fail build |

Output: `license-report.html` (color-coded table), published via **HTML Publisher Plugin**.

### Stage 8: SonarQube Analysis

```bash
./gradlew sonarqube \
  -Dsonar.host.url=http://sonarqube.utilities.svc.cluster.local:9000 \
  -Dsonar.login=${SONAR_TOKEN} \
  -Dsonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml
```

Followed by a **Quality Gate** check — blocks the build until SonarQube returns a
`PASSED` or `FAILED` verdict. Jenkins displays a SonarQube badge on the build page.

### Stage 9: Archive + Nexus Upload

**Build versioning:**

```groovy
def versionCode = env.BUILD_NUMBER.toInteger()
def versionName = sh(script: "git describe --tags --always", returnStdout: true).trim()
def apkName = "app-debug-${versionName}.apk"
```

**Nexus upload:**

```bash
curl -u ${NEXUS_USER}:${NEXUS_PASS} \
  --upload-file app/build/outputs/apk/debug/${apkName} \
  http://nexus.utilities.svc.cluster.local:8081/repository/android-releases/${apkName}
```

**Jenkins archive:**

```groovy
archiveArtifacts artifacts: "**/${apkName}", fingerprint: true
junit '**/build/test-results/**/*.xml'
publishHTML([...])  // SAST, SCA, License reports
```

---

## Security Toolchain

### Tools Summary

| Stage | Tool | License | Integration |
|---|---|---|---|
| SAST | Android Lint | Apache-2.0 | Gradle plugin |
| SAST | SpotBugs + FindSecBugs | LGPL | Gradle plugin |
| SAST | Semgrep OSS | LGPL-2.1 | CLI in agent |
| SCA/OSA | OWASP Dependency-Check | Apache-2.0 | CLI in agent |
| License | Gradle License Plugin | Apache-2.0 | Gradle plugin |
| Coverage | JaCoCo | EPL-2.0 | Gradle plugin |
| Quality | SonarQube Community | LGPL-3.0 | K8s Deployment |

### Android SDK Agent Image

The `thyrlian/android-sdk:latest` image includes Android SDK and build tools.
Additional tools installed at container startup (or baked into a custom image):

```dockerfile
FROM thyrlian/android-sdk:latest
RUN apt-get update && apt-get install -y curl unzip python3-pip
RUN pip3 install semgrep
RUN wget -q https://github.com/jeremylong/DependencyCheck/releases/download/v9.0.9/dependency-check-9.0.9-release.zip \
    && unzip dependency-check-*.zip -d /opt/ && rm dependency-check-*.zip
ENV PATH="/opt/dependency-check/bin:$PATH"
```

> **Recommendation:** Build and push this custom image to a registry accessible from your cluster
> to avoid re-downloading tools on every build (especially relevant for Option B ephemeral agents).

---

## SonarQube Integration

### Initial Setup

1. Access SonarQube UI: `http://<node-ip>:<nodePort>` (or via port-forward)
2. Create project: `sunflower-android`
3. Generate token → store in Jenkins credential as `SONAR_TOKEN`
4. Install **SonarQube Scanner for Jenkins** plugin
5. Configure server in `Manage Jenkins → Configure System → SonarQube servers`

### Quality Gate (default)

| Metric | Condition | Action |
|---|---|---|
| Coverage | < 60% on new code | Fail |
| Duplications | > 3% on new code | Warn |
| Security Hotspots | Any unreviewed | Fail |
| Reliability Rating | < A | Fail |

Quality gate thresholds are configurable in SonarQube UI under `Quality Gates`.

---

## Build Versioning and Nexus Upload

### Version Format

```
versionCode: <BUILD_NUMBER>              e.g. 42
versionName: <git-describe>             e.g. v1.0.0-42-gabcdef7
APK filename: app-debug-v1.0.0-42-gabcdef7.apk
```

`git describe` falls back to `<short-sha>` if no tags exist (e.g. `gabcdef7`).

### Nexus Repository Structure

```
android-releases/
├── app-debug-v1.0.0-1-gabc1234.apk
├── app-debug-v1.0.0-2-gdef5678.apk
└── app-debug-v1.0.0-42-gabcdef7.apk
```

### Nexus Initial Setup

```bash
# Port-forward to access Nexus UI
kubectl port-forward -n utilities svc/nexus 8081:8081

# Default credentials: admin / (see /nexus-data/admin.password in pod)
kubectl exec -n utilities deploy/nexus -- cat /nexus-data/admin.password

# Create raw hosted repository named 'android-releases' via UI or API
```

Store Nexus credentials in Jenkins as `NEXUS_USER` / `NEXUS_PASS` (Secret Text credentials).

---

## LLM Failure Analysis

### Endpoints

| Endpoint | Type | Model used |
|---|---|---|
| `192.168.1.10:11434` | Ollama | `Qwen2.5-Coder:14B-Instruct` (primary) |
| `192.168.1.10:11434` | Ollama | `codellama` (optional, skipped if absent) |
| `192.168.1.24:21434` | llama.cpp | `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` |

### Adding CodeLlama to Ollama

```bash
# Pull CodeLlama 7B (~3.8GB)
curl -X POST http://192.168.1.10:11434/api/pull \
  -H "Content-Type: application/json" \
  -d '{"name": "codellama"}'

# Or 13B variant (~7.4GB, better analysis)
curl -X POST http://192.168.1.10:11434/api/pull \
  -H "Content-Type: application/json" \
  -d '{"name": "codellama:13b"}'
```

### Prompt Template

```
You are a CI/CD build failure analyst. Analyze this Android build failure.

Failed Stage: <stage>
Error Snippet:
<error>

Console Log Tail (last 200 lines):
<log>

Respond in Markdown. Include:
1. ## Root Cause — 1-2 sentences
2. ## Likely Fix — numbered steps with code blocks where relevant
3. ## Confidence — High / Medium / Low with reasoning
4. ## Failure Flow — a Mermaid flowchart showing which step broke and why
5. ## References — relevant docs, Gradle flags, or Android SDK notes
```

### Script: `shared/llm-analysis.sh`

- Input: `$FAILED_STAGE`, `$ERROR_SNIPPET`, `$LOG_TAIL`
- Calls Ollama via `/api/generate` (streaming disabled, `"stream": false`)
- Calls llama.cpp via `/v1/chat/completions` (OpenAI-compatible)
- Checks CodeLlama presence via `GET /api/tags | grep codellama` before calling
- Each model response saved as `<model>-analysis.md`
- All responses merged into `llm-analysis.md` with build metadata header
- Timeout per call: 120 seconds

### Output: `llm-analysis.md` structure

```markdown
# Jenkins Build Failure Analysis

## Build Metadata
- Job: ...
- Build #: ...
- Failed Stage: ...
- Timestamp: ...

---

## Analysis: Qwen2.5-Coder:14B-Instruct (Ollama)
<model response including Mermaid diagram>

---

## Analysis: DeepSeek-Coder-V2-Lite (llama.cpp)
<model response including Mermaid diagram>

---

## Analysis: CodeLlama (Ollama)
<model response or "Model not available — run: curl -X POST http://192.168.1.10:11434/api/pull -d '{"name":"codellama"}'">
```

---

## Email Notifications

**Recipient:** `hello.dk@outlook.com`
**Sent via:** Postfix relay at `smtp.utilities.svc.cluster.local:25`
**Plugin:** Jenkins Email Extension (email-ext)

### Success Email

```
Subject: [JENKINS SUCCESS] <job> #<build> — All stages passed

Body (HTML):
├── Build metadata (job, build#, branch, duration, timestamp)
├── Stage summary table with per-stage duration
├── Security Summary:
│   ├── SAST:     X findings (High / Med / Low)
│   ├── SCA/OSA:  X CVEs (Critical / High / Med / Low)
│   └── Licenses: Y deps — Z clean, N flagged (list flagged)
├── SonarQube: coverage %, quality gate status, link
└── Nexus artifact link: http://nexus.../android-releases/app-debug-<ver>.apk

Attachment: build-<number>-artifacts.zip
  ├── app-debug-<versionName>.apk
  ├── semgrep-report.html
  ├── spotbugs-report.html
  ├── dependency-check-report.html
  ├── license-report.html
  └── console.log
```

### Failure Email

```
Subject: [JENKINS FAILURE] <job> #<build> — Failed at: <stage>

Body (HTML):
├── Build metadata (job, build#, branch, duration, timestamp)
├── Failed stage + error snippet (monospace block)
├── Security findings that triggered failure (if applicable)
├── LLM Analysis — Qwen2.5-Coder (Ollama)
├── LLM Analysis — DeepSeek-Coder-V2-Lite (llama.cpp)
└── LLM Analysis — CodeLlama (if available)

Attachment: build-<number>-logs.zip
  ├── console.log (full)
  ├── test-results/ (JUnit XML)
  ├── available security reports (up to point of failure)
  └── llm-analysis.md (all model responses + Mermaid diagrams)
```

---

## Jenkins UI Reports

| Report | Plugin | Location in UI |
|---|---|---|
| SonarQube badge + link | SonarQube Scanner | Build page header |
| OWASP CVE trend graph | OWASP Dependency-Check | Job sidebar widget |
| SAST findings trend | Warnings Next Generation | Job sidebar widget |
| SAST HTML report | HTML Publisher | Build → HTML Reports |
| SCA HTML report | HTML Publisher | Build → HTML Reports |
| License compliance | HTML Publisher | Build → HTML Reports |
| Test results | JUnit plugin | Build → Test Results |
| Code coverage | JaCoCo plugin | Build → Coverage Report |

---

## LLM Endpoints Reference

### Check available models

```bash
# Ollama
curl http://192.168.1.10:11434/api/tags | jq '.models[].name'

# llama.cpp
curl http://192.168.1.24:21434/v1/models | jq '.data[].id'
```

### Currently available models

**Ollama (`192.168.1.10:11434`)**

| Model | Size | Purpose in pipeline |
|---|---|---|
| `Qwen2.5-Coder:14B-Instruct` | 14.8B Q4_K_M | Primary failure analysis |
| `deepseek-coder:6.7b` | 6.7B Q4_0 | Available (not used by default) |
| `llama3.1:8b` | 8.0B Q4_K_M | Available (not used by default) |
| `gemma3:12b-it-qat` | 12.2B Q4_0 | Available (not used by default) |
| `codellama` | 7B or 13B | Optional third analysis voice |

**llama.cpp (`192.168.1.24:21434`)**

| Model | Size | Purpose in pipeline |
|---|---|---|
| `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` | 15.7B | Secondary failure analysis |
