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
