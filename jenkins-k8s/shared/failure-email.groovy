<%
import java.text.SimpleDateFormat

def build       = binding.getVariable("build")
def jobName     = build.project.name
def buildNum    = build.number
def duration    = build.durationString.replace(' and counting', '')
def sdf         = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
def timestamp   = sdf.format(new Date(build.startTimeInMillis))
def failedStage = ""
try { failedStage = build.getEnvironment()['FAILED_STAGE'] ?: "Unknown" } catch(e) { failedStage = "Unknown" }

def llmHtml = ""
try {
    def ws = build.workspace
    if (ws) {
        def f = ws.child("llm-analysis.md")
        if (f.exists()) {
            def md = f.readToString()
            // closure-based replacements — avoids $1 syntax that breaks SimpleTemplateEngine
            md = md.replaceAll(/(?m)^# (.+)/) { m, g -> "<h1 style='color:#333'>${g}</h1>" }
            md = md.replaceAll(/(?m)^## (.+)/) { m, g -> "<h2 style='color:#1976d2;border-left:4px solid #1976d2;padding-left:8px'>${g}</h2>" }
            md = md.replaceAll(/(?m)^### (.+)/) { m, g -> "<h3 style='color:#555'>${g}</h3>" }
            md = md.replaceAll(/`([^`]+)`/) { m, g -> "<code style='background:#e8e8e8;padding:2px 4px;border-radius:3px;font-size:12px'>${g}</code>" }
            md = md.replaceAll(/\*\*([^*]+)\*\*/) { m, g -> "<strong>${g}</strong>" }
            md = md.replaceAll(/```[a-z]*\n([\s\S]*?)```/, { m, g -> "<pre style='background:#2d2d2d;color:#f8f8f2;padding:12px;border-radius:4px;overflow-x:auto;font-size:12px'>${g}</pre>" })
            md = md.replaceAll(/\n\n/, '</p><p style="margin:8px 0">')
            llmHtml = "<p style='margin:8px 0'>${md}</p>"
        }
    }
} catch(e) {
    llmHtml = "<p><em>LLM analysis not available: ${e.message}</em></p>"
}
%>
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"/></head>
<body style="margin:0;padding:0;background:#f5f5f5;font-family:Arial,sans-serif;">
<div style="max-width:900px;margin:20px auto;background:white;border-radius:8px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,0.1);">

  <div style="background:#c62828;color:white;padding:24px 32px;">
    <div style="font-size:22px;font-weight:bold;">Build Failed</div>
    <div style="margin-top:6px;opacity:0.9;font-size:14px;">${jobName} #${buildNum} &mdash; Failed at: <strong>${failedStage}</strong></div>
  </div>

  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 16px;font-size:15px;color:#333;border-left:4px solid #c62828;padding-left:12px;">Build Details</h2>
    <table style="border-collapse:collapse;width:100%;">
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;width:140px;font-size:13px;">Job</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${jobName}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Build #</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${buildNum}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Failed Stage</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;color:#c62828;font-weight:bold;">${failedStage}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Duration</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${duration}</td></tr>
      <tr><td style="padding:7px 12px;border:1px solid #eee;background:#f9f9f9;font-weight:bold;font-size:13px;">Timestamp</td><td style="padding:7px 12px;border:1px solid #eee;font-size:13px;">${timestamp}</td></tr>
    </table>
  </div>

  <div style="padding:24px 32px;border-bottom:1px solid #eee;">
    <h2 style="margin:0 0 8px;font-size:15px;color:#333;border-left:4px solid #1976d2;padding-left:12px;">LLM Failure Analysis</h2>
    <p style="margin:0 0 16px;font-size:12px;color:#666;">Analyzed by Qwen2.5-Coder / DeepSeek-Coder / CodeLlama via Ollama.</p>
    <div style="font-size:13px;line-height:1.7;">${llmHtml}</div>
  </div>

  <div style="padding:14px 32px;background:#f9f9f9;font-size:11px;color:#888;">
    Jenkins CI/CD &nbsp;|&nbsp; utilities namespace
  </div>
</div>
</body>
</html>
