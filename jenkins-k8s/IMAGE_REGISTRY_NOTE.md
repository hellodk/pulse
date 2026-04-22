# Android Agent Image

Image name: android-jenkins-agent:latest
Loaded into cluster via: k0s containerd import (`docker save android-jenkins-agent:latest | sudo k0s ctr images import -`)

Full image reference in k0s containerd: `docker.io/library/android-jenkins-agent:latest`

Use this reference in:
  - jenkins-k8s/option-b-ephemeral-agents/jenkins-casc-config.yaml (image field)
  - jenkins-k8s/option-c-static-agent/jenkins-agent-deploy.yaml (image field)

## Tool Versions Verified

- semgrep: 1.160.0
- dependency-check: 9.0.9
- jq: 1.7
- java: OpenJDK 17.0.12

## Build Note

The base image (thyrlian/android-sdk:latest) uses Ubuntu 24.04, which enforces PEP 668
(externally-managed Python). The Dockerfile required `--break-system-packages` added to
the `pip3 install semgrep` command to succeed.

## Cluster Details

Cluster type: k0s (not k3s)
Container runtime: containerd 1.7.30
Import command used: `docker save android-jenkins-agent:latest | sudo k0s ctr images import -`
