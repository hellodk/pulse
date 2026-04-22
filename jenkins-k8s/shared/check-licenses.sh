#!/bin/bash
# check-licenses.sh <license-json-dir> <output-html>
# Reads Gradle License Plugin JSON output, generates color-coded HTML report.
# Exit code 1 if any BLOCKED licenses found.
set -euo pipefail

INPUT_DIR="${1:-build/reports/licenses}"
OUTPUT_HTML="${2:-reports/license-report.html}"

ALLOWED="MIT Apache-2.0 BSD-2-Clause BSD-3-Clause ISC Unlicense CC0-1.0 Apache-1.1"
REVIEW="LGPL-2.0 LGPL-2.1 LGPL-3.0 MPL-2.0 CDDL-1.0 EPL-1.0 EPL-2.0 EUPL-1.2"
BLOCKED="AGPL-3.0 AGPL-1.0 GPL-2.0 GPL-3.0 SSPL-1.0 BUSL-1.1"

FAIL=0
TOTAL=0
ALLOWED_COUNT=0
REVIEW_COUNT=0
BLOCKED_COUNT=0

declare -A DEP_LICENSE
declare -A DEP_URL

# Parse Gradle License Plugin JSON (format: [{name, license, licenseUrl},...])
if ls "${INPUT_DIR}"/*.json &>/dev/null 2>&1; then
    while IFS= read -r entry; do
        dep=$(echo "$entry" | jq -r '.project // .name // "unknown"')
        lic=$(echo "$entry" | jq -r '.license // .spdxLicense // "Unknown"')
        url=$(echo "$entry" | jq -r '.licenseUrl // ""')
        DEP_LICENSE["$dep"]="$lic"
        DEP_URL["$dep"]="$url"
    done < <(jq -c '.[]' "${INPUT_DIR}"/*.json 2>/dev/null || true)
fi

mkdir -p "$(dirname "$OUTPUT_HTML")"

rows=""
for dep in "${!DEP_LICENSE[@]}"; do
    lic="${DEP_LICENSE[$dep]}"
    url="${DEP_URL[$dep]}"
    class="amber"
    status="Review Required"
    TOTAL=$((TOTAL + 1))

    if echo " $BLOCKED " | grep -qF " $lic "; then
        class="red"; status="BLOCKED — Copyleft Risk"; BLOCKED_COUNT=$((BLOCKED_COUNT + 1)); FAIL=1
    elif echo " $ALLOWED " | grep -qF " $lic "; then
        class="green"; status="Allowed"; ALLOWED_COUNT=$((ALLOWED_COUNT + 1))
    else
        REVIEW_COUNT=$((REVIEW_COUNT + 1))
    fi

    link="${dep}"
    [ -n "$url" ] && link="<a href='${url}' target='_blank'>${dep}</a>"
    rows+="<tr class='${class}'><td>${link}</td><td>${lic}</td><td>${status}</td></tr>\n"
done

cat > "$OUTPUT_HTML" <<HTML
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>License Compliance Report</title>
<style>
  body{font-family:Arial,sans-serif;margin:2em;}
  h1{color:#333;}
  .summary{display:flex;gap:16px;margin-bottom:16px;}
  .badge{padding:8px 16px;border-radius:4px;font-weight:bold;font-size:14px;}
  .badge-green{background:#d4edda;color:#155724;}
  .badge-amber{background:#fff3cd;color:#856404;}
  .badge-red{background:#f8d7da;color:#721c24;}
  table{border-collapse:collapse;width:100%;}
  th,td{border:1px solid #ddd;padding:8px;text-align:left;font-size:13px;}
  th{background:#4a4a4a;color:white;}
  .green{background:#d4edda;}
  .amber{background:#fff3cd;}
  .red{background:#f8d7da;font-weight:bold;}
  a{color:#0066cc;}
</style>
</head>
<body>
<h1>License Compliance Report</h1>
<p>Generated: $(date -u)</p>
<div class="summary">
  <div class="badge badge-green">Allowed: ${ALLOWED_COUNT}</div>
  <div class="badge badge-amber">Review: ${REVIEW_COUNT}</div>
  <div class="badge badge-red">Blocked: ${BLOCKED_COUNT}</div>
</div>
<table>
<tr><th>Dependency</th><th>License</th><th>Status</th></tr>
$(printf '%s' "$rows")
</table>
<hr/>
<p style="font-size:12px;color:#666;">
  <strong>Blocked licenses (fail build):</strong> AGPL-3.0, AGPL-1.0, GPL-2.0, GPL-3.0, SSPL-1.0, BUSL-1.1<br/>
  <strong>Review licenses (warn):</strong> LGPL, MPL-2.0, CDDL, EPL<br/>
  <strong>Allowed licenses (pass):</strong> MIT, Apache-2.0, BSD, ISC, Unlicense, CC0
</p>
</body>
</html>
HTML

echo "License report: ${TOTAL} total, ${ALLOWED_COUNT} allowed, ${REVIEW_COUNT} review, ${BLOCKED_COUNT} blocked"
[ $FAIL -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL (blocked licenses found)"
exit $FAIL
