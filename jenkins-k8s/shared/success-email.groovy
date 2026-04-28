<%
import java.text.SimpleDateFormat

def build       = binding.getVariable("build")
def jobName     = build.project.name
def buildNum    = build.number
def duration    = build.durationString.replace(' and counting', '')
def sdf         = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
def timestamp   = sdf.format(new Date(build.startTimeInMillis))
def versionName = ""
try { versionName = build.getEnvironment()['VERSION_NAME'] ?: "unknown" } catch(e) { versionName = "unknown" }
def apkFilename = "app-debug-${versionName}.apk"
def nexusUrl    = "http://nexus.utilities.svc.cluster.local:8081/repository/android-releases/${apkFilename}"
%>
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"/></head>
<body style="margin:0;padding:0;background:#f5f5f5;font-family:Arial,sans-serif;">
<div style="max-width:900px;margin:20px auto;background:white;border-radius:8px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,0.1);">

  <div style="background:#2e7d32;color:white;padding:24px 32px;">
    <div style="font-size:22px;font-weight:bold;">Build Succeeded</div>
    <div style="margin-top:6px;opacity:0.9;font-size:14px;">${jobName} #${buildNum} &mdash; All stages passed</div>
  </div>

  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #2e7d32;padding-left:12px;">Build Details</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;width:140px;font-size:13px;">Job</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${jobName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Build #</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${buildNum}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Version</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;font-family:monospace;">${versionName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Duration</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${duration}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Timestamp</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${timestamp}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">APK</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;"><a href="${nexusUrl}" style="color:#0066cc;">${apkFilename}</a></td></tr>
    </table>
  </div>

  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #2e7d32;padding-left:12px;">Pipeline Stages</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr style="background:#4a4a4a;color:white;"><th style="padding:8px 12px;text-align:left;font-size:13px;">Stage</th><th style="padding:8px 12px;text-align:left;font-size:13px;">Status</th></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Checkout</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Validate Environment</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Build Debug APK</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Unit Tests</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SAST</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SCA / OSA</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">License Compliance</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">SonarQube Analysis</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;background:#d4edda;">Passed</td></tr>
      <tr style="background:#d4edda;"><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Archive + Nexus Upload</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">Passed</td></tr>
    </table>
  </div>

  <div style="padding:14px 32px;background:#f9f9f9;font-size:11px;color:#888;">
    Jenkins CI/CD &nbsp;|&nbsp; utilities namespace
  </div>
</div>
</body>
</html>
