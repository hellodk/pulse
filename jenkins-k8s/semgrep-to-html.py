#!/usr/bin/env python3
import json
import sys
from html import escape

def convert(json_file, html_file):
    with open(json_file) as f:
        data = json.load(f)

    results = data.get('results', [])
    errors  = data.get('errors', [])

    rows = ""
    for r in results:
        sev  = escape(r.get('extra', {}).get('severity', 'INFO').upper())
        rule = escape(r.get('check_id', ''))
        path = escape(r.get('path', ''))
        line = str(r.get('start', {}).get('line', ''))
        msg  = escape(r.get('extra', {}).get('message', ''))
        rows += f"<tr class='{sev}'><td>{sev}</td><td>{rule}</td><td>{path}:{line}</td><td>{msg}</td></tr>\n"

    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Semgrep SAST Report</title>
<style>
  body {{font-family:Arial,sans-serif;margin:2em;}}
  h1 {{color:#333;}}
  table {{border-collapse:collapse;width:100%;}}
  th,td {{border:1px solid #ddd;padding:8px;text-align:left;font-size:13px;}}
  th {{background:#4a4a4a;color:white;}}
  .ERROR {{background:#f8d7da;}}
  .WARNING {{background:#fff3cd;}}
  .INFO {{background:#d1ecf1;}}
</style>
</head>
<body>
<h1>Semgrep SAST Report</h1>
<p>Total findings: <strong>{len(results)}</strong> &nbsp;|&nbsp; Parse errors: {len(errors)}</p>
<table>
<tr><th>Severity</th><th>Rule</th><th>File:Line</th><th>Message</th></tr>
{rows}
</table>
</body>
</html>"""

    with open(html_file, 'w') as f:
        f.write(html)
    print(f"Report written to {html_file} ({len(results)} findings)")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.json> <output.html>")
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2])
