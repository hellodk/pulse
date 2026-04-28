# Operations Runbook — Jenkins CI/CD on k0s

Day-to-day operations reference for the Jenkins CI/CD stack in the `utilities` namespace.

---

## Quick Reference

| Resource | URL / Command |
|----------|--------------|
| Jenkins UI | `http://100.89.50.27:30881` (admin / admin123) |
| Nexus | `http://100.89.50.27:30882` (admin / admin123) |
| JFrog | `http://100.89.50.27:30082` (admin / password) |
| SonarQube | `http://100.89.50.27:30900` |
| All pods | `kubectl get pods -n utilities` |
| Jenkins logs | `kubectl logs -n utilities deployment/jenkins-c -c jenkins --since=10m` |
| ios-agent log | `ssh dk@100.102.68.75 "tail -20 ~/jenkins-agent/agent-error.log"` |

---

## Disk Pressure

**Symptom:** Pods stuck in `Pending`, `kubectl describe node` shows `disk-pressure:NoSchedule` taint.

**Automatic fix:** `disk-cleanup-cronjob` runs daily at 02:00 UTC. If disk drops below 85%, it removes the taint automatically.

**Manual fix:**
```bash
# Remove taint immediately
kubectl taint node --all node.kubernetes.io/disk-pressure:NoSchedule-

# Free space on the k0s host (disk is mostly user data under /home/dk/)
# Check what's consuming space
du -sh /home/dk/*/ 2>/dev/null | sort -rh | head -10

# Prune unused container images (run on k0s host)
nsenter -t 1 -m -- crictl rmi --prune 2>/dev/null || true

# Clean Jenkins workspaces older than 3 days
find /home/jenkins/agent/workspace -maxdepth 1 -mindepth 1 -type d -mtime +3 -exec rm -rf {} +
```

**Threshold:** kubelet adds the taint at ~85% disk usage (937 GB total, ~794 GB threshold).

---

## Jenkins Pod Restart

Jenkins uses a PVC for `JENKINS_HOME`. All job configs, credentials, and build history survive pod restarts.

```bash
kubectl rollout restart deployment/jenkins-c -n utilities
kubectl rollout status deployment/jenkins-c -n utilities --timeout=120s
```

After restart, init scripts in `jenkins-c-init` ConfigMap re-run:
- `01-security.groovy` — sets admin credentials (idempotent)
- `02-node.groovy` — creates `android-agent` node (idempotent)
- `03-smtp.groovy` — configures Mailer descriptor: host, port, `useSsl=false`, replyTo
- `04-sonarqube.groovy` — adds SonarQube token + Nexus credentials, configures SonarQube server

The Mailer config (`useSsl=false`) is also written to `JENKINS_HOME/hudson.tasks.Mailer.xml` on the PVC, so it persists independently.

---

## Android Agent Restart

The android-build-agent pod mounts the `llm-analysis.sh` from a ConfigMap. Restarting the pod picks up ConfigMap changes automatically.

```bash
kubectl rollout restart deployment/android-build-agent -n utilities
```

If the agent goes offline mid-build: the build will wait for the agent to reconnect (up to 5 minutes). If the pod restarts, the JNLP secret is stored in the `jenkins-agent-secret` Kubernetes Secret and the agent will auto-reconnect.

---

## Updating llm-analysis.sh

The script lives in `jenkins-k8s/shared/llm-analysis.sh` and is mounted into the android-agent via ConfigMap.

```bash
# Update ConfigMap
kubectl create configmap llm-analysis-script \
  --from-file=llm-analysis.sh=jenkins-k8s/shared/llm-analysis.sh \
  -n utilities --dry-run=client -o yaml | kubectl apply -f -

# Rollout new pod to pick up the change
kubectl rollout restart deployment/android-build-agent -n utilities
```

---

## Email Not Delivered

**Check Postfix logs:**
```bash
kubectl logs -n utilities deployment/postfix --since=30m | \
  grep -E "(status=sent|status=deferred|535|554|550)"
```

**status=deferred means relay auth failed.** Verify the Mailtrap secret:
```bash
kubectl get secret mailtrap-smtp-creds -n utilities -o jsonpath='{.data.username}' | base64 -d
# Should be: api
```

**Check Jenkins is sending to Postfix (not directly):**
```bash
kubectl exec -n utilities deployment/jenkins-c -c jenkins -- \
  cat /var/jenkins_home/hudson.tasks.Mailer.xml
# Should show: smtpHost = smtp.utilities.svc.cluster.local, useSsl = false
```

**Test SMTP manually from any pod:**
```bash
kubectl exec -n utilities deployment/jenkins-c -c jenkins -- \
  curl -s smtp://smtp.utilities.svc.cluster.local:25 \
  --mail-from hello@demomailtrap.co \
  --mail-rcpt reject@hellodk.io \
  -T /dev/null
```

---

## iOS Agent Offline

**Check status in Jenkins:**
```bash
curl --user admin:admin123 -s \
  "http://100.89.50.27:30881/computer/ios-agent/api/json?tree=offline,offlineCauseReason" | \
  python3 -c "import sys,json;d=json.load(sys.stdin);print('offline:',d['offline'],'reason:',d.get('offlineCauseReason',''))"
```

**Check Mac Mini:**
```bash
ssh dk@100.102.68.75 "
  launchctl list | grep jenkins
  tail -5 ~/jenkins-agent/agent-error.log
"
```

**Restart agent service:**
```bash
ssh dk@100.102.68.75 "
  launchctl unload ~/Library/LaunchAgents/com.jenkins.ios-agent.plist
  sleep 2
  launchctl load ~/Library/LaunchAgents/com.jenkins.ios-agent.plist
"
```

**If agent shows online but builds fail immediately (no stages run):** The agent connection may be stale. Disconnect via Jenkins and let launchd reconnect:
```bash
curl --user admin:admin123 \
  -H "Jenkins-Crumb: $(curl --user admin:admin123 -s http://100.89.50.27:30881/crumbIssuer/api/json | python3 -c 'import sys,json;print(json.load(sys.stdin)["crumb"])')" \
  -X POST "http://100.89.50.27:30881/computer/ios-agent/doDisconnect?offlineMessage=manual+reset"
```

---

## Updating iOS Job Configurations

The iOS job configs are stored in `jenkins-k8s/ios/job-configs/`. To update a job:

1. Edit the relevant Jenkinsfile in `jenkins-k8s/ios/option-*/Jenkinsfile`
2. Commit and push to master
3. The next build will pick up the change automatically (SCM-based pipeline)

To update the job's Jenkins configuration (e.g., change the git branch):
```bash
CRUMB=$(curl --user admin:admin123 -s "http://100.89.50.27:30881/crumbIssuer/api/json" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['crumb'])")
curl --user admin:admin123 -H "Jenkins-Crumb: $CRUMB" \
  -X POST "http://100.89.50.27:30881/job/ios-swift-xcodebuild/config.xml" \
  --data-binary @jenkins-k8s/ios/job-configs/ios-swift-xcodebuild.xml \
  -H "Content-Type: application/xml"
```

---

## Rotating Secrets

### Jenkins JNLP Agent Secret (android-agent)

If the Jenkins controller PVC is wiped, a new secret is generated:
```bash
# Get new secret from Jenkins UI → Manage Jenkins → Nodes → android-agent
kubectl create secret generic jenkins-agent-secret \
  --from-literal=JENKINS_SECRET=<NEW_SECRET> \
  -n utilities --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment/android-build-agent -n utilities
```

### iOS Agent Secret

The ios-agent secret is stored in `ansible/group_vars/mac_mini.yml`. If it changes:
```bash
# Get new secret
curl --user admin:admin123 -s \
  "http://100.89.50.27:30881/computer/ios-agent/slave-agent.jnlp" | grep -o 'secret[^<]*'

# Update plist on Mac Mini
ssh dk@100.102.68.75 "
  /usr/libexec/PlistBuddy -c 'Set :ProgramArguments:5 <NEW_SECRET>' \
    ~/Library/LaunchAgents/com.jenkins.ios-agent.plist
  launchctl unload ~/Library/LaunchAgents/com.jenkins.ios-agent.plist
  launchctl load ~/Library/LaunchAgents/com.jenkins.ios-agent.plist
"
```

### Mailtrap API Token

```bash
kubectl create secret generic mailtrap-smtp-creds \
  --from-literal=username=api \
  --from-literal=password=<NEW_TOKEN> \
  -n utilities --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment/postfix -n utilities
```

---

## SonarQube Quality Gate Issues

If SonarQube analysis fails with authentication errors:
```bash
# Verify token works
curl -u sqa_...: "http://100.89.50.27:30900/api/system/status"

# Update token in Jenkins credentials
# Jenkins UI → Manage Jenkins → Credentials → sonar-token → Update
```

If SonarQube pod is restarting (OOM), it needs 2GB+ RAM:
```bash
kubectl describe pod -n utilities -l app=sonarqube | grep -E "(OOM|Limits|Requests|memory)"
```

---

## Triggering Builds via API

```bash
# Get crumb
CRUMB=$(curl --user admin:admin123 -s "http://100.89.50.27:30881/crumbIssuer/api/json" | \
  python3 -c "import sys,json;d=json.load(sys.stdin);print(d['crumb'])")

# Trigger build
curl --user admin:admin123 -H "Jenkins-Crumb: $CRUMB" \
  -X POST "http://100.89.50.27:30881/job/android-build/build"

# Check last build status
curl --user admin:admin123 -s \
  "http://100.89.50.27:30881/job/android-build/lastBuild/api/json?tree=number,result,building" | \
  python3 -m json.tool
```
