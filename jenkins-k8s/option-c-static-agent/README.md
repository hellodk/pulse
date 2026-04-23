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
