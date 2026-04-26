#!/usr/bin/env bash
# JFrog Artifactory OSS setup script for Android/iOS artifact repositories.
# Note: Repository creation API (PUT /api/repositories/{key}) requires JFrog Pro.
# This script documents the setup process and upload verification.
#
# Usage: NODE_IP=192.168.1.10 bash jenkins-k8s/shared/jfrog-setup.sh
#
# For OSS users: Repositories must be created via UI or Pro API.
# This script assumes android-local and ios-local exist or will be created manually.
#
set -euo pipefail

NODE_IP="${NODE_IP:-192.168.1.10}"
JFROG="http://${NODE_IP}:30082/artifactory"
CREDS="${CREDS:-admin:password}"

echo "=== JFrog Artifactory Setup ==="
echo "Server: ${JFROG}"
echo ""

# Test authentication
echo "=== Testing authentication ==="
if curl -sf -u "${CREDS}" "${JFROG}/api/system/ping" > /dev/null 2>&1; then
    echo "OK - Authentication successful"
else
    echo "FAILED - Check credentials: ${CREDS}"
    exit 1
fi

# List existing repositories
echo ""
echo "=== Existing repositories ==="
curl -sf -u "${CREDS}" \
    "${JFROG}/api/repositories?type=local" \
    | python3 -c "import sys,json; [print(' - ' + r['key']) for r in json.load(sys.stdin)]" || echo "(no repositories found)"

# Note about creating repositories
echo ""
echo "=== Repository Creation ==="
echo "NOTE: JFrog Artifactory OSS does NOT support the repository creation API."
echo "Repository creation is a Pro-only feature."
echo ""
echo "To create android-local and ios-local repositories:"
echo "1. Access the JFrog UI at: http://${NODE_IP}:30082/ui"
echo "2. Upgrade to JFrog Artifactory Pro, OR"
echo "3. Use JFrog REST API (Pro only) to create repos programmatically"
echo ""

# Test artifact upload capability (to verify repo access)
echo "=== Testing artifact upload (requires existing repo) ==="
UPLOAD_TEST="/tmp/jfrog-test-$$.txt"
echo "test artifact" > "${UPLOAD_TEST}"

if curl -sf -u "${CREDS}" \
    -X PUT \
    --upload-file "${UPLOAD_TEST}" \
    "${JFROG}/example-repo-local/test-artifact-$$.txt" 2>/dev/null; then
    echo "OK - Upload capability verified (example-repo-local)"

    # Clean up test artifact
    curl -sf -u "${CREDS}" \
        -X DELETE \
        "${JFROG}/example-repo-local/test-artifact-$$.txt" 2>/dev/null || true
    echo "OK - Test artifact cleaned up"
else
    echo "FAILED - Upload test failed"
fi

rm -f "${UPLOAD_TEST}"

echo ""
echo "=== Configuration Summary ==="
echo "JFrog Server: http://${NODE_IP}:30082/ui"
echo "Admin Credentials: admin:password (default)"
echo "Expected Repositories:"
echo "  - android-local (for APK uploads)"
echo "  - ios-local (for IPA uploads)"
echo ""
echo "=== Done ==="
