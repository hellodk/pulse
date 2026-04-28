# Jenkins CI/CD on Kubernetes — Android & iOS Pipelines

LLM-assisted CI/CD running on a single-node k0s cluster in the `utilities` namespace. Android builds run on a containerised agent with the full Android SDK. iOS builds run on a Mac Mini via a JNLP static agent registered through Tailscale.

---

## Architecture

```
┌─────────────────────────────────── k0s (single node: cylon) ───────────────────────────────────┐
│  namespace: utilities                                                                            │
│                                                                                                  │
│  ┌──────────────┐   JNLP:50000   ┌──────────────────────┐                                       │
│  │  Jenkins C   │◄───────────────│  android-build-agent  │  hellodk/android-jenkins-agent:latest│
│  │  :30881      │                │  (k8s pod)            │  Android SDK + Gradle + security tools│
│  └──────┬───────┘                └──────────────────────┘                                       │
│         │                                                                                        │
│         │  JNLP:30500 (NodePort)                                                                │
│         │◄────────────────────────────────────── Mac Mini M2 (ios-agent) ── Tailscale           │
│         │                                        /Users/dk/jenkins-agent                        │
│         │                                        Xcode 26, nvm, rbenv, CocoaPods               │
│         │                                                                                        │
│  ┌──────┴───────────────────────────────────────────────────────────────────────────────────┐   │
│  │  Supporting Services                                                                      │   │
│  │  Nexus :8081          SonarQube :9000      JFrog :8082/:30082   Postfix (smtp:25)        │   │
│  │  PostgreSQL (SonarQube)                    Ollama :11434                                 │   │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘

NodePorts: Jenkins HTTP=30881  Jenkins JNLP=30500  JFrog=30082
```

---

## Components

| Manifest | Service | Port | Purpose |
|----------|---------|------|---------|
| `shared/pvcs.yaml` | PVCs | — | Jenkins home, Gradle cache, Nexus data, SonarQube data, JFrog data, postgres data |
| `namespace.yaml` | Namespace | — | `utilities` |
| `shared/postfix-deploy.yaml` | Postfix SMTP | 25 | Relay to Mailtrap live SMTP (STARTTLS on 587) |
| `shared/postgres-deploy.yaml` | PostgreSQL 15 | 5432 | SonarQube backend |
| `shared/sonarqube-deploy.yaml` | SonarQube CE | 9000 | SAST quality gate |
| `shared/nexus-deploy.yaml` | Nexus OSS | 8081 | APK/IPA artifact storage |
| `shared/jfrog-deploy.yaml` | JFrog Artifactory OSS | 8082/30082 | Alternate artifact storage |
| `shared/network-policy.yaml` | NetworkPolicy | — | Calico rules + NodePort ingress for Jenkins |
| `shared/disk-cleanup-cronjob.yaml` | CronJob | — | Daily 02:00 UTC disk cleanup, auto-removes disk-pressure taint |
| `option-c-static-agent/` | Jenkins C | 30881 | Controller + static JNLP android-agent |
| `option-b-ephemeral-agents/` | Jenkins B | — | Controller with per-build ephemeral pod agents |

---

## Android Pipeline

**Job:** `android-build` — reads `shared/Jenkinsfile` from git (SCM-based).

### Stages

```
Checkout → Validate Environment → Build Debug APK → Unit Tests
  → SAST (Semgrep + SpotBugs) → SCA/OSA (OWASP dependency-check)
  → License Compliance → SonarQube Analysis
  → Archive + Nexus Upload → JFrog Upload
```

On **failure**: `llm-analysis.sh` queries both Ollama endpoints, picks the best available model from each, and writes `llm-analysis.md`. The failure email embeds the LLM analysis HTML.

### LLM Analysis

`shared/llm-analysis.sh` — runs on the android-agent pod, mounted from ConfigMap `llm-analysis-script`.

- Queries `http://100.89.50.27:11434` and `http://100.104.14.62:21434`
- Ranks models by capability: qwen2.5-coder > qwen2.5 > deepseek-coder > codellama > llama3 > others
- Writes `llm-analysis.md` → archived as Jenkins artifact → failure email template reads from `build.getRootDir()/archive/`

### Email Notifications

**Sender:** `hello@demomailtrap.co` via Mailtrap live SMTP  
**Recipient:** `reject@hellodk.io`  
**Templates:** `shared/failure-email.groovy`, `shared/success-email.groovy`  
Both are Groovy SimpleTemplate (not scripts) — all code in `<% %>` blocks, HTML in the body.

**Key**: Jenkins → Postfix (port 25, no SSL) → Mailtrap (port 587, STARTTLS, user=`api`)

---

## iOS Pipelines

Three pipelines targeting `ios-agent` (Mac Mini M2). Each reads its Jenkinsfile from the git repo via `CpsScmFlowDefinition`. See `ios/README.md` for full details.

| Job | Jenkinsfile | App |
|-----|------------|-----|
| `ios-swift-xcodebuild` | `ios/option-1-swift/Jenkinsfile` | FoodTruck (Swift/SPM) |
| `ios-fastlane` | `ios/option-2-fastlane/Jenkinsfile` | FoodTruck (Fastlane) |
| `ios-react-native` | `ios/option-3-react-native/Jenkinsfile` | Mattermost-mobile (React Native) |

All three use `options { skipDefaultCheckout(true) }` — see `ios/README.md` for why.

---

## Deploy Order

```bash
# 1. Create namespace
kubectl apply -f namespace.yaml

# 2. Create PVCs
kubectl apply -f shared/pvcs.yaml

# 3. NetworkPolicy
kubectl apply -f shared/network-policy.yaml

# 4. Supporting services (order matters: postgres before sonarqube)
kubectl apply -f shared/postfix-deploy.yaml
kubectl apply -f shared/postgres-deploy.yaml
kubectl apply -f shared/sonarqube-deploy.yaml
kubectl apply -f shared/nexus-deploy.yaml
kubectl apply -f shared/jfrog-deploy.yaml

# 5. Jenkins controller
kubectl apply -f option-c-static-agent/jenkins-config.yaml
kubectl apply -f option-c-static-agent/jenkins-deploy.yaml
kubectl rollout status deployment/jenkins-c -n utilities --timeout=300s

# 6. Get android-agent JNLP secret from Jenkins UI, update agent-secret.yaml, then:
kubectl apply -f option-c-static-agent/agent-secret.yaml
kubectl apply -f option-c-static-agent/jenkins-agent-deploy.yaml

# 7. Email templates
kubectl apply -f shared/email-templates-configmap.yaml

# 8. LLM analysis script
kubectl create configmap llm-analysis-script -n utilities \
  --from-file=llm-analysis.sh=shared/llm-analysis.sh

# 9. Patch android-agent to mount llm-analysis.sh from ConfigMap
# (see option-c-static-agent/jenkins-agent-deploy.yaml for volume mount)

# 10. Disk cleanup CronJob
kubectl apply -f shared/disk-cleanup-cronjob.yaml

# 11. Create Jenkins jobs (via UI or API)
# Android: curl -X POST .../createItem?name=android-build --data-binary @shared/android-build-job.xml
# iOS: use ios/job-configs/*.xml
```

---

## Mailtrap Credentials

Postfix relay uses a Kubernetes Secret:

```bash
kubectl create secret generic mailtrap-smtp-creds \
  --from-literal=username=api \
  --from-literal=password=<MAILTRAP_API_TOKEN> \
  -n utilities
```

---

## Operational Notes

### Disk Pressure Taint

kubelet adds `node.kubernetes.io/disk-pressure:NoSchedule` at ~85% disk usage. The `disk-cleanup-cronjob` removes it automatically at 02:00 UTC if disk drops below 85%. Manual removal:

```bash
kubectl taint node --all node.kubernetes.io/disk-pressure:NoSchedule-
```

The 481 GB under `/home/dk/` (VMs, media) is the main consumer — not CI artifacts.

### Jenkins Email Config Persistence

The Mailer descriptor config (`useSsl=false`, `smtpHost`, `replyTo`) is written to `JENKINS_HOME/hudson.tasks.Mailer.xml` on the PVC and survives pod restarts. The init script `03-smtp.groovy` explicitly sets `setUseSsl(false)` for clean cold-start.

### ios-agent Stability

The Mac Mini ios-agent runs as a launchd service (`com.jenkins.ios-agent`). Key env vars in the plist: `PATH` (includes nvm/node, rbenv/pod), `LANG=en_US.UTF-8` (required by CocoaPods), `NVM_DIR`, `RBENV_ROOT`.

If the agent appears offline: `ssh dk@100.102.68.75 "launchctl list | grep jenkins"`

### Updating llm-analysis.sh

The script is mounted from ConfigMap — patch the ConfigMap and rollout the deployment:

```bash
kubectl create configmap llm-analysis-script -n utilities \
  --from-file=llm-analysis.sh=shared/llm-analysis.sh --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment/android-build-agent -n utilities
```
