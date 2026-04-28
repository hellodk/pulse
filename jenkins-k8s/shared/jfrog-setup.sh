#!/usr/bin/env bash
# JFrog Artifactory OSS setup verification script.
# Repo creation API requires Artifactory Pro; this script uses
# example-repo-local with /android/ and /ios/ subdirectory conventions.
# Usage: NODE_IP=192.168.1.10 bash jenkins-k8s/shared/jfrog-setup.sh
set -euo pipefail
NODE_IP="${NODE_IP:-192.168.1.10}"
JFROG="http://${NODE_IP}:30082/artifactory"
CREDS="${CREDS:-admin:password}"

echo "=== JFrog Artifactory OSS Setup ==="
curl -sf -u "${CREDS}" "${JFROG}/api/system/ping" && echo " ping OK"

echo "=== Existing repositories ==="
curl -sf -u "${CREDS}" "${JFROG}/api/repositories" \
  | python3 -c "import sys,json; [print(' -', r['key']) for r in json.load(sys.stdin)]"

echo ""
echo "=== Upload path conventions (OSS: subdirs in example-repo-local) ==="
echo "Android APKs : ${JFROG}/example-repo-local/android/<apk-name>"
echo "iOS IPAs     : ${JFROG}/example-repo-local/ios/<ipa-name>"

echo ""
echo "=== Testing upload paths ==="
echo "test" | curl -sf -u "${CREDS}" -X PUT --upload-file - \
  "${JFROG}/example-repo-local/android/.keep" && echo "android path writable"
echo "test" | curl -sf -u "${CREDS}" -X PUT --upload-file - \
  "${JFROG}/example-repo-local/ios/.keep" && echo "ios path writable"
curl -sf -u "${CREDS}" -X DELETE "${JFROG}/example-repo-local/android/.keep" 2>/dev/null || true
curl -sf -u "${CREDS}" -X DELETE "${JFROG}/example-repo-local/ios/.keep" 2>/dev/null || true

echo "=== Done. JFrog UI: http://${NODE_IP}:30082/ui  (admin/password) ==="
