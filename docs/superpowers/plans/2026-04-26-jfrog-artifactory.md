# JFrog Artifactory — Deploy on K8s + Android/iOS Upload Stages

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy JFrog Artifactory OSS on Kubernetes (`utilities` namespace) and add upload stages to the Android pipeline (Jenkinsfile) and iOS pipeline designs (both options).

**Architecture:** JFrog Artifactory OSS runs as a single Deployment in `utilities` with a 10Gi PVC and NodePort 30082. The Android agent pod uploads via the ClusterIP (covered by existing `allow-jenkins-ci-internal` NetworkPolicy after adding `jfrog` to the selector). The iOS Mac Mini agent uploads directly to the NodePort. Both use HTTP PUT to the Artifactory REST API.

**Tech Stack:** JFrog Artifactory OSS 7.x (`releases-docker.jfrog.io/jfrog/artifactory-oss:latest`), Kubernetes, curl for uploads, Jenkins credentials binding.

**Cluster context:**
- Namespace: `utilities`, node: `cylon` (192.168.1.10 / 100.89.50.27), k0s
- Pods have no internet — all images must be pulled on host and imported via `k0s ctr images import`
- Existing NetworkPolicy `allow-jenkins-ci-internal` must be extended to include `jfrog`
- JFrog ClusterIP will be used for pod→JFrog communication
- NodePort 30082 is free

---

## File Map

```
jenkins-k8s/
└── shared/
    ├── jfrog-deploy.yaml          # PVC + Deployment + Service for JFrog
    ├── network-policy.yaml        # MODIFY: add jfrog to pod selector
    ├── Jenkinsfile                # MODIFY: add JFrog Upload stage (Android)
    └── jfrog-setup.sh             # One-shot setup script (repos + API key)

docs/superpowers/specs/
└── 2026-04-26-ios-pipeline-design.md  # MODIFY: add JFrog stages to both iOS options
```

---

## Phase 1 — Deploy JFrog Artifactory

### Task 1: Pull JFrog image and import into k0s

**Files:** none (host-side operation)

- [ ] **Step 1: Pull JFrog Artifactory OSS image on the host**

```bash
docker pull releases-docker.jfrog.io/jfrog/artifactory-oss:latest
```

Expected: `Status: Downloaded newer image ...`

- [ ] **Step 2: Tag with Docker Hub name for clarity**

```bash
docker tag releases-docker.jfrog.io/jfrog/artifactory-oss:latest \
  hellodk/artifactory-oss:latest
```

- [ ] **Step 3: Import into k0s containerd**

```bash
docker save releases-docker.jfrog.io/jfrog/artifactory-oss:latest \
  | sudo k0s ctr images import -
```

Expected last line: `done`

- [ ] **Step 4: Verify image is in k0s**

```bash
sudo k0s ctr images list 2>/dev/null | grep artifactory
```

Expected: shows `releases-docker.jfrog.io/jfrog/artifactory-oss:latest`

- [ ] **Step 5: Commit a note**

```bash
cd /home/dk/Documents/git/testing-grounds/.worktrees/jenkins-pipeline
echo "releases-docker.jfrog.io/jfrog/artifactory-oss:latest" >> jenkins-k8s/IMAGE_REGISTRY_NOTE.md
git add jenkins-k8s/IMAGE_REGISTRY_NOTE.md
git commit -m "docs: note JFrog Artifactory OSS image imported into k0s"
```

---

### Task 2: Write and apply JFrog Artifactory Kubernetes manifests

**Files:**
- Create: `jenkins-k8s/shared/jfrog-deploy.yaml`

- [ ] **Step 1: Write `jenkins-k8s/shared/jfrog-deploy.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jfrog-data
  namespace: utilities
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jfrog
  namespace: utilities
  labels:
    app: jfrog
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jfrog
  template:
    metadata:
      labels:
        app: jfrog
    spec:
      securityContext:
        fsGroup: 1030
        runAsUser: 1030
      initContainers:
      - name: fix-permissions
        image: busybox:1.28
        securityContext:
          runAsUser: 0
        command: ['sh', '-c', 'chown -R 1030:1030 /var/opt/jfrog/artifactory']
        volumeMounts:
        - name: jfrog-data
          mountPath: /var/opt/jfrog/artifactory
      containers:
      - name: artifactory
        image: releases-docker.jfrog.io/jfrog/artifactory-oss:latest
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8082
          name: http
        env:
        - name: JF_SHARED_DATABASE_TYPE
          value: "derby"
        volumeMounts:
        - name: jfrog-data
          mountPath: /var/opt/jfrog/artifactory
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "3Gi"
            cpu: "2"
        readinessProbe:
          httpGet:
            path: /artifactory/api/system/ping
            port: 8082
          initialDelaySeconds: 120
          periodSeconds: 15
          failureThreshold: 10
        livenessProbe:
          httpGet:
            path: /artifactory/api/system/ping
            port: 8082
          initialDelaySeconds: 180
          periodSeconds: 30
          failureThreshold: 5
      volumes:
      - name: jfrog-data
        persistentVolumeClaim:
          claimName: jfrog-data
---
apiVersion: v1
kind: Service
metadata:
  name: jfrog
  namespace: utilities
spec:
  selector:
    app: jfrog
  type: NodePort
  ports:
  - name: http
    port: 8082
    targetPort: 8082
    nodePort: 30082
```

- [ ] **Step 2: Apply and verify objects created**

```bash
kubectl apply -f jenkins-k8s/shared/jfrog-deploy.yaml
kubectl get pvc jfrog-data -n utilities
kubectl get deployment jfrog -n utilities
kubectl get service jfrog -n utilities
```

Expected:
- PVC: Bound
- Deployment: created (0/1 initially — JFrog takes 2-3 minutes to start)
- Service: NodePort on 30082

- [ ] **Step 3: Wait for JFrog to become ready**

```bash
kubectl rollout status deployment/jfrog -n utilities --timeout=300s
```

Expected: `deployment "jfrog" successfully rolled out`

If timeout, check logs:
```bash
kubectl logs -n utilities deploy/jfrog --tail=20
```

- [ ] **Step 4: Verify JFrog ping endpoint responds**

```bash
NODE_IP=192.168.1.10
curl -sf "http://${NODE_IP}:30082/artifactory/api/system/ping"
```

Expected: `OK`

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/shared/jfrog-deploy.yaml
git commit -m "feat: deploy JFrog Artifactory OSS on k8s (utilities namespace, NodePort 30082)"
```

---

### Task 3: Initial JFrog setup — admin password, repositories, API key

**Files:**
- Create: `jenkins-k8s/shared/jfrog-setup.sh`

- [ ] **Step 1: Change default admin password**

JFrog's default credentials are `admin` / `password`. Change via REST API:

```bash
NODE_IP=192.168.1.10

# Change admin password to admin123 (consistent with other services)
curl -sf -u admin:password \
  -X POST "http://${NODE_IP}:30082/artifactory/api/security/users/authorization/changePassword" \
  -H "Content-Type: application/json" \
  -d '{"userName":"admin","oldPassword":"password","newPassword":"admin123"}' \
  && echo "password changed"
```

If the above returns 401, the password was already changed. Try `admin:admin123`.

- [ ] **Step 2: Create `android-local` generic repository**

```bash
curl -sf -u admin:admin123 \
  -X PUT "http://${NODE_IP}:30082/artifactory/api/repositories/android-local" \
  -H "Content-Type: application/json" \
  -d '{
    "rclass": "local",
    "packageType": "generic",
    "description": "Android APK releases",
    "repoLayoutRef": "simple-default"
  }' && echo "android-local created"
```

Expected: `{ "rclass" : "local" ... }`

- [ ] **Step 3: Create `ios-local` generic repository**

```bash
curl -sf -u admin:admin123 \
  -X PUT "http://${NODE_IP}:30082/artifactory/api/repositories/ios-local" \
  -H "Content-Type: application/json" \
  -d '{
    "rclass": "local",
    "packageType": "generic",
    "description": "iOS IPA releases",
    "repoLayoutRef": "simple-default"
  }' && echo "ios-local created"
```

- [ ] **Step 4: Verify both repos exist**

```bash
curl -sf -u admin:admin123 \
  "http://${NODE_IP}:30082/artifactory/api/repositories?type=local" \
  | python3 -c "import sys,json; [print(r['key']) for r in json.load(sys.stdin)]"
```

Expected output includes: `android-local` and `ios-local`

- [ ] **Step 5: Write setup script for documentation**

Create `jenkins-k8s/shared/jfrog-setup.sh`:

```bash
#!/usr/bin/env bash
# Run once after fresh JFrog deployment to configure repos and admin password.
# Usage: NODE_IP=192.168.1.10 bash jenkins-k8s/shared/jfrog-setup.sh
set -euo pipefail
NODE_IP="${NODE_IP:-192.168.1.10}"
JFROG="http://${NODE_IP}:30082/artifactory"

echo "=== Changing admin password ==="
curl -sf -u admin:password -X POST "${JFROG}/api/security/users/authorization/changePassword" \
  -H "Content-Type: application/json" \
  -d '{"userName":"admin","oldPassword":"password","newPassword":"admin123"}' \
  && echo "OK" || echo "already changed"

echo "=== Creating android-local ==="
curl -sf -u admin:admin123 -X PUT "${JFROG}/api/repositories/android-local" \
  -H "Content-Type: application/json" \
  -d '{"rclass":"local","packageType":"generic","description":"Android APK releases","repoLayoutRef":"simple-default"}' \
  && echo "OK"

echo "=== Creating ios-local ==="
curl -sf -u admin:admin123 -X PUT "${JFROG}/api/repositories/ios-local" \
  -H "Content-Type: application/json" \
  -d '{"rclass":"local","packageType":"generic","description":"iOS IPA releases","repoLayoutRef":"simple-default"}' \
  && echo "OK"

echo "=== Done. Access JFrog at http://${NODE_IP}:30082/ui ==="
```

```bash
chmod +x jenkins-k8s/shared/jfrog-setup.sh
```

- [ ] **Step 6: Commit**

```bash
git add jenkins-k8s/shared/jfrog-setup.sh
git commit -m "feat: JFrog initial setup — admin password, android-local and ios-local repos"
```

---

## Phase 2 — NetworkPolicy + Credentials

### Task 4: Extend NetworkPolicy and add JFrog credentials to Jenkins C

**Files:**
- Modify: `jenkins-k8s/shared/network-policy.yaml`

- [ ] **Step 1: Read current NetworkPolicy**

```bash
cat jenkins-k8s/shared/network-policy.yaml | grep -A5 "matchExpressions"
```

The current policy selector lists: `sonarqube`, `postgres`, `nexus`, `postfix`, `jenkins-b`, `jenkins-c`, `android-build-agent`.

- [ ] **Step 2: Add `jfrog` to both ingress and egress pod selectors in network-policy.yaml**

In `jenkins-k8s/shared/network-policy.yaml`, find both `operator: In` / `values:` blocks (there are two: one in `ingress.from` and one in `egress.to`) and add `- jfrog` to each values list.

The `podSelector.matchExpressions` block becomes:
```yaml
        matchExpressions:
        - key: app
          operator: In
          values:
          - sonarqube
          - postgres
          - nexus
          - postfix
          - jenkins-b
          - jenkins-c
          - android-build-agent
          - jfrog
```
Apply this change to BOTH the `spec.podSelector.matchExpressions` (top-level) AND the `ingress.from.podSelector` and `egress.to.podSelector` blocks.

- [ ] **Step 3: Apply updated NetworkPolicy**

```bash
kubectl apply -f jenkins-k8s/shared/network-policy.yaml
kubectl get networkpolicy allow-jenkins-ci-internal -n utilities
```

- [ ] **Step 4: Add JFrog credential to Jenkins C via Script Console**

```bash
NODE_IP=192.168.1.10
curl -sf -u admin:admin123 -c /tmp/jc.txt \
  "http://${NODE_IP}:30881/crumbIssuer/api/json" > /tmp/crumb.json
CF=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumbRequestField'])")
CV=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumb'])")

curl -sf -u admin:admin123 -b /tmp/jc.txt \
  -H "${CF}: ${CV}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode 'script=
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*

def store = SystemCredentialsProvider.getInstance().getStore()
store.getCredentials(Domain.global()).findAll { it.id == "jfrog-creds" }.each { store.removeCredentials(Domain.global(), it) }
store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
  CredentialsScope.GLOBAL, "jfrog-creds", "JFrog Artifactory credentials", "admin", "admin123"))
println "Done: " + store.getCredentials(Domain.global()).collect { it.id }
' "http://${NODE_IP}:30881/scriptText"
```

Expected output includes: `jfrog-creds`

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/shared/network-policy.yaml
git commit -m "feat: add jfrog to NetworkPolicy allow list and Jenkins C credentials"
```

---

## Phase 3 — Android Pipeline JFrog Upload

### Task 5: Add JFrog Upload stage to Android Jenkinsfile

**Files:**
- Modify: `jenkins-k8s/shared/Jenkinsfile`

The JFrog ClusterIP is needed. Get it at plan execution time:
```bash
JFROG_IP=$(kubectl get svc jfrog -n utilities -o jsonpath='{.spec.clusterIP}')
echo "JFrog ClusterIP: $JFROG_IP"
```

- [ ] **Step 1: Add `JFROG_CREDS` to environment block**

In `jenkins-k8s/shared/Jenkinsfile`, find the `environment {` block and add:

```groovy
    environment {
        ANDROID_HOME  = '/opt/android-sdk'
        SONAR_TOKEN   = credentials('sonar-token')
        NEXUS_CREDS   = credentials('nexus-creds')
        JFROG_CREDS   = credentials('jfrog-creds')
        FAILED_STAGE  = ''
    }
```

- [ ] **Step 2: Add JFrog Upload stage after Archive + Nexus Upload**

After the `stage('Archive + Nexus Upload')` closing `}` and before the closing `}` of `stages {`, add:

```groovy
        stage('JFrog Upload') {
            steps {
                script {
                    def apkPath = sh(
                        script: "find app/build/outputs/apk/debug -name '*.apk' | head -1",
                        returnStdout: true
                    ).trim()
                    def jfrogIp = sh(
                        script: "kubectl get svc jfrog -n utilities -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo '192.168.1.10'",
                        returnStdout: true
                    ).trim()
                    sh """
                        cp "${apkPath}" "${env.APK_FILENAME}"
                        curl -sf \
                            -u "${env.JFROG_CREDS_USR}:${env.JFROG_CREDS_PSW}" \
                            -X PUT \
                            --upload-file "${env.APK_FILENAME}" \
                            "http://${jfrogIp}:8082/artifactory/android-local/${env.APK_FILENAME}" \
                            && echo "Uploaded to JFrog: ${env.APK_FILENAME}"
                    """
                }
            }
            post { failure { script { env.FAILED_STAGE = 'JFrog Upload' } } }
        }
```

- [ ] **Step 3: Verify Jenkinsfile syntax is valid Groovy (no unbalanced braces)**

```bash
grep -c "^        stage(" jenkins-k8s/shared/Jenkinsfile
```

Expected: 10 (the 9 original stages plus the new JFrog Upload stage).

- [ ] **Step 4: Recreate the inline Jenkins job with updated Jenkinsfile**

```bash
NODE_IP=192.168.1.10
curl -sf -u admin:admin123 -c /tmp/jc.txt \
  "http://${NODE_IP}:30881/crumbIssuer/api/json" > /tmp/crumb.json
CF=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumbRequestField'])")
CV=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumb'])")

# Delete old job
curl -sf -u admin:admin123 -b /tmp/jc.txt -H "${CF}: ${CV}" \
  -X POST "http://${NODE_IP}:30881/job/android-build/doDelete" && echo "deleted"

# Rebuild inline XML
python3 - <<'PYEOF'
import xml.sax.saxutils as x
with open('jenkins-k8s/shared/Jenkinsfile') as f:
    script = f.read()
job_xml = f"""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>Android CI/CD pipeline with JFrog upload</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script>{x.escape(script)}</script>
    <sandbox>true</sandbox>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>"""
with open('/tmp/android-build-inline.xml', 'w') as f:
    f.write(job_xml)
print("XML written")
PYEOF

curl -sf -u admin:admin123 -c /tmp/jc.txt "http://${NODE_IP}:30881/crumbIssuer/api/json" > /tmp/crumb.json
CF=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumbRequestField'])")
CV=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumb'])")
curl -sf -u admin:admin123 -b /tmp/jc.txt \
  -H "${CF}: ${CV}" -H "Content-Type: application/xml" \
  --data-binary @/tmp/android-build-inline.xml \
  "http://${NODE_IP}:30881/createItem?name=android-build" && echo "job created"
```

- [ ] **Step 5: Commit**

```bash
git add jenkins-k8s/shared/Jenkinsfile
git commit -m "feat: add JFrog Upload stage to Android Jenkinsfile (android-local repo)"
```

---

## Phase 4 — iOS Pipeline JFrog Upload

### Task 6: Add JFrog upload to iOS pipeline designs (both options)

**Files:**
- Modify: `docs/superpowers/specs/2026-04-26-ios-pipeline-design.md`

- [ ] **Step 1: Add `jfrog-creds` credential to iOS Pipeline 1 (Jenkinsfile-first) environment block**

In the iOS Pipeline 1 Jenkinsfile block inside the spec, add to the `environment` section:

```groovy
        JFROG_CREDS     = credentials('jfrog-creds')
```

- [ ] **Step 2: Add JFrog Upload stage to iOS Pipeline 1 after Archive + Nexus Upload**

After the Nexus upload step in Pipeline 1, add:

```groovy
        stage('JFrog Upload') {
            steps {
                script {
                    def ipaPath = sh(
                        script: "find build -name '*.ipa' | head -1",
                        returnStdout: true
                    ).trim()
                    if (ipaPath) {
                        sh """
                            curl -sf \
                                -u "${env.JFROG_CREDS_USR}:${env.JFROG_CREDS_PSW}" \
                                -X PUT \
                                --upload-file "${ipaPath}" \
                                "http://192.168.1.10:30082/artifactory/ios-local/${env.IPA_NAME}" \
                                && echo "Uploaded to JFrog: ${env.IPA_NAME}"
                        """
                    } else {
                        echo "No IPA found — skipping JFrog upload"
                    }
                }
            }
        }
```

Note: iOS agent (Mac Mini) uses the NodePort `192.168.1.10:30082` directly since it is not a pod (no ClusterIP access).

- [ ] **Step 3: Add JFrog Fastlane lane to iOS Pipeline 2 (Fastlane-first)**

In the `fastlane/Fastfile` block in the spec, add a `upload_jfrog` lane after `upload_nexus`:

```ruby
  # ── Upload to JFrog Artifactory ──────────────────────────────────────────
  lane :upload_jfrog do
    ipa_path = lane_context[SharedValues::IPA_OUTPUT_PATH]
    ipa_name = File.basename(ipa_path)
    sh("curl -sf " \
       "-u #{ENV['JFROG_USER']}:#{ENV['JFROG_PASS']} " \
       "-X PUT --upload-file '#{ipa_path}' " \
       "'http://192.168.1.10:30082/artifactory/ios-local/#{ipa_name}' " \
       "&& echo 'Uploaded to JFrog: #{ipa_name}'")
  end
```

And update the `ci` lane to call `upload_jfrog`:

```ruby
  lane :ci do
    test
    build
    sast
    upload_nexus
    upload_jfrog
  end
```

- [ ] **Step 4: Add `JFROG_USER`/`JFROG_PASS` env passthrough to Pipeline 2 Jenkinsfile**

In the Pipeline 2 Jenkinsfile `Upload` stage:

```groovy
        stage('Upload') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'nexus-creds',
                        usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS'),
                    usernamePassword(credentialsId: 'jfrog-creds',
                        usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')
                ]) {
                    sh 'bundle exec fastlane upload_nexus'
                    sh 'bundle exec fastlane upload_jfrog'
                }
            }
        }
```

- [ ] **Step 5: Update Security Equivalence Table in iOS spec to include JFrog row**

Add to the table:
```
| Nexus upload | Nexus upload (IPA) + JFrog Artifactory (ios-local) |
```

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-04-26-ios-pipeline-design.md
git commit -m "feat: add JFrog upload to iOS pipeline designs (both Jenkinsfile and Fastlane options)"
```

---

## Phase 5 — Verify

### Task 7: Trigger Android pipeline and verify JFrog upload

**Files:** none (operational verification)

- [ ] **Step 1: Trigger the Android build**

```bash
NODE_IP=192.168.1.10
curl -sf -u admin:admin123 -c /tmp/jc.txt \
  "http://${NODE_IP}:30881/crumbIssuer/api/json" > /tmp/crumb.json
CF=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumbRequestField'])")
CV=$(python3 -c "import json; d=json.load(open('/tmp/crumb.json')); print(d['crumb'])")
curl -sf -u admin:admin123 -b /tmp/jc.txt -H "${CF}: ${CV}" \
  -X POST "http://${NODE_IP}:30881/job/android-build/build" && echo "triggered"
```

- [ ] **Step 2: Monitor the build until JFrog stage completes**

Poll every 30 seconds:
```bash
watch -n30 'curl -sf -u admin:admin123 \
  "http://192.168.1.10:30881/job/android-build/lastBuild/api/json" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get(\"result\") or \"RUNNING\")"'
```

Wait for `SUCCESS` or `UNSTABLE`.

- [ ] **Step 3: Verify APK in JFrog android-local repo**

```bash
curl -sf -u admin:admin123 \
  "http://192.168.1.10:30082/artifactory/api/storage/android-local" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(c['uri']) for c in d.get('children',[])]"
```

Expected: shows `.apk` file URI.

- [ ] **Step 4: Verify download URL works**

```bash
APK=$(curl -sf -u admin:admin123 \
  "http://192.168.1.10:30082/artifactory/api/storage/android-local" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['children'][0]['uri'])")
echo "APK at: http://192.168.1.10:30082/artifactory/android-local${APK}"
curl -sf -u admin:admin123 \
  "http://192.168.1.10:30082/artifactory/android-local${APK}" \
  -o /tmp/verify.apk && ls -lh /tmp/verify.apk
```

Expected: file downloads without error.

- [ ] **Step 5: Commit nothing — verification only**

---

## Self-Review

**Spec coverage:**
- ✅ JFrog deployed on k8s (Tasks 1-3)
- ✅ Android APK upload to JFrog (Task 5)
- ✅ iOS IPA upload to JFrog in both pipeline designs (Task 6)
- ✅ NetworkPolicy extended (Task 4)
- ✅ Credentials added to Jenkins C (Task 4)
- ✅ End-to-end verification (Task 7)

**Placeholder scan:** No TBD, TODO, or "similar to" references found.

**Type consistency:**
- `JFROG_CREDS_USR` / `JFROG_CREDS_PSW` — standard Jenkins `credentials()` binding convention, consistent with `NEXUS_CREDS_USR` / `NEXUS_CREDS_PSW` already in the Jenkinsfile
- `jfrog-creds` credential ID used consistently across Task 4 (creation), Task 5 (Jenkinsfile), Task 6 (iOS spec)
- Port `8082` (ClusterIP path) and `30082` (NodePort path) used consistently: pods use 8082 via ClusterIP, Mac Mini uses 30082 via NodePort
