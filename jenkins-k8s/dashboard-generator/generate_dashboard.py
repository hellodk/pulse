#!/usr/bin/env python3
"""
generate_dashboard.py
Queries Jenkins REST API (server-side) and produces a fully static HTML dashboard.
No JavaScript in output. Credentials are never written to the HTML.
"""

import argparse
import json
import re
import sys
import urllib.request
import urllib.error
import base64
from datetime import datetime, timezone, timedelta


# ── helpers ───────────────────────────────────────────────────────────────────

def jenkins_get(url: str, user: str, password: str, timeout: int = 15) -> dict | None:
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {token}"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"  [WARN] GET {url} → {e}", file=sys.stderr)
        return None


def fetch_text(url: str, user: str, password: str, timeout: int = 10) -> str:
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {token}"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode(errors="replace")
    except Exception:
        return ""


def ms_to_human(ms: int) -> str:
    if ms < 0:
        return "—"
    s = ms // 1000
    if s < 60:
        return f"{s}s"
    m, s = divmod(s, 60)
    if m < 60:
        return f"{m}m {s}s"
    h, m = divmod(m, 60)
    return f"{h}h {m}m"


def ts_to_ist(ts_ms: int) -> str:
    if not ts_ms:
        return "—"
    dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    ist = dt.astimezone(timezone(timedelta(hours=5, minutes=30)))
    return ist.strftime("%d %b %Y %H:%M IST")


def result_badge(result: str) -> str:
    icons = {
        "SUCCESS":  ("✅", "#166534", "#dcfce7", "#bbf7d0"),
        "FAILURE":  ("❌", "#991b1b", "#fef2f2", "#fecaca"),
        "UNSTABLE": ("⚠️", "#92400e", "#fffbeb", "#fde68a"),
        "ABORTED":  ("⛔", "#374151", "#f3f4f6", "#d1d5db"),
        "RUNNING":  ("🔄", "#1e40af", "#eff6ff", "#bfdbfe"),
        "UNKNOWN":  ("❓", "#374151", "#f9fafb", "#e5e7eb"),
    }
    icon, fg, bg, border = icons.get(result, icons["UNKNOWN"])
    return (
        f'<span style="display:inline-flex;align-items:center;gap:5px;'
        f'padding:4px 10px;border-radius:20px;border:1px solid {border};'
        f'background:{bg};color:{fg};font-weight:700;font-size:13px;">'
        f'{icon} {result}</span>'
    )


def dot(result: str) -> str:
    colors = {
        "SUCCESS": "#16a34a",
        "FAILURE": "#dc2626",
        "UNSTABLE": "#d97706",
        "ABORTED": "#9ca3af",
    }
    c = colors.get(result, "#d1d5db")
    title = result or "Unknown"
    return (
        f'<span title="{title}" style="display:inline-block;width:10px;height:10px;'
        f'border-radius:50%;background:{c};margin:1px;" aria-label="{title}"></span>'
    )


def truncate_llm(text: str, chars: int = 600) -> str:
    if not text:
        return ""
    # strip markdown headers and blank lines for compact display
    lines = [l for l in text.splitlines() if l.strip() and not l.startswith("---")]
    joined = "\n".join(lines)
    if len(joined) > chars:
        joined = joined[:chars] + "\n…(see llm-analysis.md artifact for full report)"
    return joined


# ── main ──────────────────────────────────────────────────────────────────────

JOBS = [
    {"id": "ios-swift-xcodebuild",  "label": "iOS Swift Xcodebuild",    "icon": "🍎"},
    {"id": "ios-fastlane",          "label": "iOS Fastlane",             "icon": "🍎"},
    {"id": "ios-react-native",      "label": "iOS React Native",         "icon": "🍎"},
    {"id": "android-build",         "label": "Android Build",            "icon": "🤖"},
    {"id": "Pulse-iOS-Enterprise",  "label": "Pulse iOS Enterprise",     "icon": "🍎"},
    {"id": "devsecops/master",      "label": "DevSecOps",                "icon": "🛡️"},
]

TREE = (
    "name,lastBuild[number,result,timestamp,duration,url,"
    "actions[failedLeaves[displayName]]],"
    "builds[number,result,timestamp]"
)


def build_card(job_meta: dict, data: dict | None, base_url: str, user: str, password: str) -> str:
    icon  = job_meta["icon"]
    label = job_meta["label"]
    job_id = job_meta["id"]

    if data is None:
        return f"""
        <div style="background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:20px 24px;
                    border-top:4px solid #9ca3af;margin-bottom:0;">
          <div style="font-size:15px;font-weight:700;margin-bottom:8px;">{icon} {label}</div>
          <div style="color:#dc2626;font-size:13px;">⚠ Could not reach Jenkins API for this job</div>
        </div>"""

    lb     = data.get("lastBuild") or {}
    result = (lb.get("result") or "RUNNING" if lb else "UNKNOWN").upper()
    builds = data.get("builds") or []

    # accent colour per result
    accents = {
        "SUCCESS": "#16a34a", "FAILURE": "#dc2626",
        "UNSTABLE": "#d97706", "RUNNING": "#2563eb", "ABORTED": "#374151",
    }
    accent = accents.get(result, "#9ca3af")

    # failed stage
    failed_stage = ""
    if result == "FAILURE":
        for action in lb.get("actions") or []:
            leaves = action.get("failedLeaves") or []
            if leaves:
                failed_stage = leaves[0].get("displayName", "")
                break

    # build history dots
    dots_html = "".join(dot(b.get("result") or "UNKNOWN") for b in builds[:15])
    if not dots_html:
        dots_html = '<span style="color:#9ca3af;font-size:12px;">No history</span>'

    # LLM analysis (only for failures)
    llm_block = ""
    if result == "FAILURE" and lb.get("number"):
        artifact_url = f"{base_url}/job/{job_id}/{lb['number']}/artifact/llm-analysis.md"
        raw = fetch_text(artifact_url, user, password)
        if raw:
            snippet = truncate_llm(raw)
            llm_block = f"""
            <div style="margin-top:14px;border-top:1px solid #f3f4f6;padding-top:12px;">
              <div style="font-size:11px;font-weight:700;color:#6b7280;text-transform:uppercase;
                          letter-spacing:.05em;margin-bottom:6px;">🤖 LLM Failure Analysis</div>
              <pre style="font-family:'Courier New',monospace;font-size:11px;line-height:1.55;
                          background:#0d1117;color:#c9d1d9;padding:12px;border-radius:6px;
                          white-space:pre-wrap;word-break:break-word;max-height:240px;
                          overflow-y:auto;margin:0;">{snippet}</pre>
            </div>"""

    build_url = lb.get("url") or f"{base_url}/job/{job_id}/"
    build_num = lb.get("number", "—")
    duration  = ms_to_human(lb.get("duration") or -1)
    timestamp = ts_to_ist(lb.get("timestamp") or 0)

    failed_row = ""
    if failed_stage:
        failed_row = f"""
          <tr>
            <td style="color:#555;font-weight:600;padding:3px 0;width:120px;">Failed Stage</td>
            <td style="color:#dc2626;font-weight:700;">{failed_stage}</td>
          </tr>"""

    return f"""
    <div style="background:#fff;border:1px solid #e5e7eb;border-radius:10px;
                border-top:4px solid {accent};
                box-shadow:0 1px 3px rgba(0,0,0,.05);margin-bottom:0;">
      <div style="padding:18px 20px 14px;">
        <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px;flex-wrap:wrap;">
          <div style="font-size:15px;font-weight:700;color:#111;">{icon} {label}</div>
          <div>{result_badge(result)}</div>
        </div>

        <table style="width:100%;border-collapse:collapse;font-size:12px;margin-top:10px;">
          <tr>
            <td style="color:#555;font-weight:600;padding:3px 0;width:120px;">Build</td>
            <td><a href="{build_url}" style="color:#2563eb;text-decoration:none;"
                   target="_blank">#{build_num}</a></td>
          </tr>
          <tr>
            <td style="color:#555;font-weight:600;padding:3px 0;">Triggered</td>
            <td style="color:#374151;">{timestamp}</td>
          </tr>
          <tr>
            <td style="color:#555;font-weight:600;padding:3px 0;">Duration</td>
            <td style="color:#374151;">{duration}</td>
          </tr>
          {failed_row}
        </table>

        <div style="margin-top:12px;">
          <div style="font-size:11px;color:#9ca3af;margin-bottom:4px;
                      text-transform:uppercase;letter-spacing:.04em;font-weight:600;">History</div>
          <div style="line-height:1;">{dots_html}</div>
        </div>

        {llm_block}

        <div style="margin-top:12px;">
          <a href="{build_url}" target="_blank"
             style="display:inline-block;padding:6px 14px;background:#1d4ed8;color:#fff;
                    border-radius:5px;font-size:12px;font-weight:600;text-decoration:none;">
            Open Build ↗
          </a>
        </div>
      </div>
    </div>"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jenkins-url", required=True)
    ap.add_argument("--user",        required=True)
    ap.add_argument("--password",    required=True)
    ap.add_argument("--output",      required=True)
    ap.add_argument("--refresh",     type=int, default=300,
                    help="Browser meta-refresh interval in seconds (default 300)")
    args = ap.parse_args()

    base = args.jenkins_url.rstrip("/")
    user, password = args.user, args.password

    now_ist = datetime.now(timezone(timedelta(hours=5, minutes=30))).strftime(
        "%d %b %Y %H:%M:%S IST"
    )

    print(f"Querying Jenkins at {base} …")

    # ── Fetch job data ──────────────────────────────────────────────────────
    cards_html = ""
    pass_count = fail_count = unstable_count = running_count = 0

    for job_meta in JOBS:
        job_id = job_meta["id"]
        api_url = f"{base}/job/{job_id.replace('/', '/job/')}/api/json?tree={TREE}"
        print(f"  → {job_id}")
        data = jenkins_get(api_url, user, password)

        if data:
            lb = data.get("lastBuild") or {}
            r  = (lb.get("result") or "RUNNING" if lb else "UNKNOWN").upper()
            if r == "SUCCESS":   pass_count += 1
            elif r == "FAILURE": fail_count += 1
            elif r == "UNSTABLE": unstable_count += 1
            elif r == "RUNNING":  running_count += 1

        cards_html += build_card(job_meta, data, base, user, password)

    total = len(JOBS)

    # ── Summary stat cards ──────────────────────────────────────────────────
    def stat_card(label: str, value: str, color: str, bg: str) -> str:
        return (
            f'<div style="background:#fff;border:1px solid #e5e7eb;border-radius:10px;'
            f'padding:16px 20px;border-top:4px solid {color};">'
            f'<div style="font-size:12px;color:#6b7280;font-weight:600;'
            f'text-transform:uppercase;letter-spacing:.05em;">{label}</div>'
            f'<div style="font-size:28px;font-weight:800;color:{color};margin-top:4px;">{value}</div>'
            f'</div>'
        )

    summary_html = (
        stat_card("Pipelines",  str(total),           "#1d4ed8", "#eff6ff") +
        stat_card("Passing",    str(pass_count),       "#16a34a", "#f0fdf4") +
        stat_card("Failing",    str(fail_count),       "#dc2626", "#fef2f2") +
        stat_card("Unstable",   str(unstable_count),   "#d97706", "#fffbeb")
    )

    # ── Assemble HTML ───────────────────────────────────────────────────────
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <meta http-equiv="refresh" content="{args.refresh}" />
  <title>Pulse CI — Build Intelligence Dashboard</title>
  <style>
    *, *::before, *::after {{ box-sizing: border-box; margin: 0; padding: 0; }}
    body {{
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
      background: #f3f4f6;
      color: #111827;
      min-height: 100vh;
      font-size: 14px;
    }}
    a {{ color: #2563eb; text-decoration: none; }}
    a:hover {{ text-decoration: underline; }}

    .header {{
      background: #111827;
      color: #f9fafb;
      padding: 0 24px;
      position: sticky;
      top: 0;
      z-index: 50;
    }}
    .header-inner {{
      max-width: 1200px;
      margin: 0 auto;
      height: 56px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }}
    .header-title {{
      font-size: 15px;
      font-weight: 700;
      letter-spacing: -.01em;
    }}
    .header-meta {{
      font-size: 12px;
      color: #9ca3af;
    }}
    .header-badge {{
      display: inline-flex;
      align-items: center;
      gap: 5px;
      background: #1f2937;
      border: 1px solid #374151;
      border-radius: 20px;
      padding: 3px 10px;
      font-size: 11px;
      color: #d1d5db;
    }}
    .live-dot {{
      width: 7px; height: 7px; border-radius: 50%; background: #22c55e;
    }}

    .main {{
      max-width: 1200px;
      margin: 0 auto;
      padding: 24px 16px 48px;
    }}

    .summary-grid {{
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 12px;
      margin-bottom: 24px;
    }}
    @media (max-width: 700px) {{
      .summary-grid {{ grid-template-columns: repeat(2, 1fr); }}
    }}

    .jobs-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
      gap: 16px;
    }}

    .refresh-notice {{
      font-size: 12px;
      color: #6b7280;
      text-align: center;
      margin-top: 24px;
    }}
  </style>
</head>
<body>

<header class="header">
  <div class="header-inner">
    <div>
      <span class="header-title">⚡ Pulse CI — Build Intelligence Dashboard</span>
    </div>
    <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
      <span class="header-badge"><span class="live-dot"></span> Live</span>
      <span class="header-meta">Generated: {now_ist}</span>
    </div>
  </div>
</header>

<main class="main">

  <!-- Summary -->
  <div class="summary-grid">
    {summary_html}
  </div>

  <!-- Job cards -->
  <div class="jobs-grid">
    {cards_html}
  </div>

  <p class="refresh-notice">
    🔄 Page auto-refreshes every {args.refresh // 60} minutes &nbsp;·&nbsp;
    Last generated at {now_ist} &nbsp;·&nbsp;
    Jenkins: <a href="{base}" target="_blank">{base}</a>
  </p>

</main>
</body>
</html>"""

    with open(args.output, "w") as fh:
        fh.write(html)
    print(f"Written → {args.output}")


if __name__ == "__main__":
    main()
