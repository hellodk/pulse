"""Static artifact tests for the jenkins-b iOS pipeline (issue #2).

These validate the version-controlled artifacts BEFORE anything is applied
to the cluster. Runtime verification (node online, build green, codesign -dv)
is documented in the issue acceptance criteria and run against live Jenkins.

Run:  pytest jenkins-k8s/tests/test_ios_pipeline_artifacts.py -q
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

import pytest
import yaml

REPO = Path(__file__).resolve().parents[2]
JK = REPO / "jenkins-k8s"

AGENT_NAME = "ios-m4"
AGENT_WORKDIR = "/Users/dk/jenkins-agent-b"
JENKINS_B_LAN_URL = "http://192.168.1.10:30880/"
PULSE_HOST_PATH = "/home/dk/Documents/git/pulse"
CERT_CRED_ID = "ios-dummy-p12"
CERT_PASS_CRED_ID = "ios-dummy-p12-pass"


# ── helpers ──────────────────────────────────────────────────────────────────

def load_yaml(path: Path):
    return yaml.safe_load(path.read_text())


def load_yaml_docs(path: Path):
    return list(yaml.safe_load_all(path.read_text()))


def casc_jenkins_block():
    """Return the jenkins.yaml content of the jenkins-b-casc ConfigMap."""
    doc = load_yaml(JK / "option-b-ephemeral-agents" / "jenkins-casc-config.yaml")
    return yaml.safe_load(doc["data"]["jenkins.yaml"])


NODE_XML = JK / "option-b-ephemeral-agents" / "nodes" / "ios-m4.xml"


def test_node_xml_defines_ios_m4_inbound_agent():
    """Saved node config must declare the ios-m4 inbound agent."""
    assert NODE_XML.exists(), f"{NODE_XML} missing"
    root = ET.parse(NODE_XML).getroot()
    assert root.findtext("name") == AGENT_NAME
    assert root.findtext("remoteFS") == AGENT_WORKDIR
    launcher = root.find("launcher")
    assert launcher is not None and launcher.get("class", "").endswith("JnlpLauncher")
    assert AGENT_NAME in (root.findtext("label") or "")


def bash_syntax_ok(path: Path) -> bool:
    import subprocess
    r = subprocess.run(["bash", "-n", str(path)], capture_output=True)
    return r.returncode == 0


# ── JCasC: node management boundary ──────────────────────────────────────────

def test_casc_does_not_conflict_with_rest_managed_node():
    """The jenkins-b-casc ConfigMap gets reverted at each controller boot by an
    unidentified writer (issue #2). Nodes are therefore REST-managed (stored in
    the PVC); JCasC must not declare a competing nodes block."""
    casc = casc_jenkins_block()
    assert not ((casc.get("jenkins") or {}).get("nodes")), \
        "JCasC must not define nodes while they are managed via REST/PVC"


# ── deployment: pulse repo hostPath mount ────────────────────────────────────

def test_jenkins_b_deploy_mounts_pulse_repo():
    """Controller must mount the pulse repo so CpsScmFlowDefinition file:// works."""
    deploy = load_yaml_docs(JK / "option-b-ephemeral-agents" / "jenkins-deploy.yaml")[0]
    spec = deploy["spec"]["template"]["spec"]
    vols = {v["name"]: v for v in spec["volumes"]}
    git_vols = [v for v in vols.values()
                if (v.get("hostPath") or {}).get("path") == PULSE_HOST_PATH]
    assert git_vols, f"no hostPath volume mounting {PULSE_HOST_PATH}"
    vol_name = git_vols[0]["name"]
    container = spec["containers"][0]
    mounts = {m["name"]: m for m in container["volumeMounts"]}
    assert vol_name in mounts, f"volume {vol_name} not mounted in container"
    assert mounts[vol_name]["mountPath"] == PULSE_HOST_PATH


# ── build config: real signing wired to dummy credentials ────────────────────

def test_retrorampage_signing_mode_real_with_credential_ids():
    cfg = load_yaml(JK / "apps" / "retrorampage" / "build-config.yaml")
    sign = cfg.get("signing") or {}
    assert sign.get("mode") == "real"
    assert sign.get("cert_credential_id") == CERT_CRED_ID
    assert sign.get("cert_password_credential_id") == CERT_PASS_CRED_ID
    assert sign.get("team_id"), "team_id must be set (dummy team DUMTEAM01)"


def test_credential_ids_referenced_by_config_exist_in_casc_or_sync_script():
    """Every credential ID the build config references must be creatable."""
    sync = JK / "shared" / "sync-openbao-to-jenkins.sh"
    text = sync.read_text() if sync.exists() else ""
    assert CERT_CRED_ID in text, f"{CERT_CRED_ID} not provisioned by sync script"
    assert CERT_PASS_CRED_ID in text


# ── job XML ──────────────────────────────────────────────────────────────────

JOB_XML = JK / "ios" / "job-configs" / "retrorampage-jenkinsb.xml"


def test_job_xml_exists_and_is_well_formed():
    assert JOB_XML.exists(), f"{JOB_XML} missing"
    ET.parse(JOB_XML)


def test_job_xml_points_at_generic_pipeline_and_pulse_scm():
    root = ET.parse(JOB_XML).getroot()
    assert root.tag == "flow-definition"
    definition = root.find("definition")
    assert definition is not None and \
        definition.get("class", "").endswith("CpsScmFlowDefinition")
    scm = root.find(".//scm")
    assert scm is not None
    script_path = root.find(".//scriptPath")
    assert script_path is not None and script_path.text == "jenkins-k8s/generic/ios/Jenkinsfile"
    urls = [u.text for u in root.findall(".//scm/userRemoteConfigs/*/url")]
    assert any(u and u.startswith("file:///home/dk/Documents/git/pulse") for u in urls), urls


def test_job_xml_defaults_target_ios_m4_agent():
    root = ET.parse(JOB_XML).getroot()
    params = {p.findtext("name"): p.findtext("defaultValue")
              for p in root.findall(".//parameterDefinitions/*")}
    assert params.get("AGENT_LABEL") == AGENT_NAME
    assert params.get("APP_CONFIG") == "jenkins-k8s/apps/retrorampage/build-config.yaml"


# ── Mac-side agent bootstrap script ──────────────────────────────────────────

AGENT_SH = JK / "ios" / "agent" / "setup-ios-m4-agent.sh"


def test_agent_setup_script_exists_and_valid_shell():
    assert AGENT_SH.exists(), f"{AGENT_SH} missing"
    assert bash_syntax_ok(AGENT_SH), f"{AGENT_SH} fails bash -n"


def test_agent_setup_targets_jenkins_b_lan_url_and_isolated_workdir():
    text = AGENT_SH.read_text()
    assert "192.168.1.10:30880" in text, \
        "agent script must target jenkins-b LAN URL"
    assert not re.search(r"100\.89\.50\.27|30881", text), \
        "agent script must not reference jenkins-c or Tailscale endpoints"
    assert "jenkins-agent-b" in text, "agent must use isolated workDir (not ~/jenkins-agent)"
    assert AGENT_NAME in text


def test_agent_script_does_not_embed_secrets():
    text = AGENT_SH.read_text()
    # the jenkins-c mobileapp-m3 secret leaked into a plist once; guard against repeats
    assert not re.search(r"-secret\s+[0-9a-f]{32,}", text), \
        "agent secret must come from env/arg substitution, never hardcoded"


# ── OpenBao → Jenkins sync script ────────────────────────────────────────────

SYNC_SH = JK / "shared" / "sync-openbao-to-jenkins.sh"


def test_sync_script_exists_and_valid_shell():
    assert SYNC_SH.exists(), f"{SYNC_SH} missing"
    assert bash_syntax_ok(SYNC_SH), f"{SYNC_SH} fails bash -n"


def test_sync_script_reads_openbao_not_plaintext():
    text = SYNC_SH.read_text()
    assert "bao " in text or "vault " in text or "openbao" in text.lower(), \
        "sync script must source values from OpenBao"
    assert "admin123" not in text, "no embedded passwords allowed"
    assert not re.search(r"PASSWORD=.+!", text), "no literal password assignments"


# ── unified CLI ──────────────────────────────────────────────────────────────

CLI = REPO / "scripts" / "pulse"


def test_unified_cli_exists_and_valid_shell():
    assert CLI.exists(), f"{CLI} missing"
    assert bash_syntax_ok(CLI), f"{CLI} fails bash -n"


def test_unified_cli_has_test_subcommand():
    text = CLI.read_text()
    assert re.search(r"\btest\b", text), "scripts/pulse must expose 'test'"
