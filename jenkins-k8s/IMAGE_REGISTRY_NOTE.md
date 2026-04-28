# Android Agent Image

Image reference: `hellodk/android-jenkins-agent:latest`

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
(externally-managed Python). The Dockerfile requires `--break-system-packages` on the
`pip3 install semgrep` line to succeed.

## Push History

- Pushed to Docker Hub: `hellodk/android-jenkins-agent:latest`
- Digest: sha256:181a8218a936bc2fb17e142b8e35c1254462aa307da745513615e3b33acb1007
- Also imported into k0s containerd on control-plane node (cylon) for offline use
- JFrog Artifactory OSS: releases-docker.jfrog.io/jfrog/artifactory-oss:latest (imported into k0s containerd)
