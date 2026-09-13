"""Artifact tests for the script-driven codesign freestyle pipelines.

codesign-fixed and codesign-err are FREESTYLE jobs that materialize a
commented shell script (ios/scripts/codesign-*.sh) into the workspace via a
heredoc and then execute it. The canonical script is the single source of
truth in the repo — these tests assert the job's embedded copy is byte-identical
to that script, and that both are valid bash.

Run:  pytest jenkins-k8s/tests/test_codesign_script_jobs.py -q
"""

from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import pytest

JK = Path(__file__).resolve().parents[1]

JOBS = {
    "codesign-fixed": "codesign-fixed.sh",
    "codesign-err": "codesign-err.sh",
}


@pytest.fixture(scope="module")
def job_xml():
    cache = {}
    def _load(name: str) -> str:
        if name not in cache:
            cache[name] = (JK / "ios" / "job-configs" / "freestyle" / f"{name}.xml").read_text()
        return cache[name]
    return _load


def extract_embedded_script(xml: str, script_name: str) -> str:
    root = ET.fromstring(xml)
    cmd = (root.findtext("builders/hudson.tasks.Shell/command") or "").replace("&amp;", "&")
    m = re.search(
        rf"cat > {re.escape(script_name)} <<'PULSE_CODEZEOF_UNIQUE'\n(.*?)\nPULSE_CODEZEOF_UNIQUE",
        cmd,
        re.S,
    )
    assert m, f"heredoc embedding for {script_name} not found in job shell"
    return m.group(1)


@pytest.mark.parametrize("name", JOBS)
def test_job_xml_exists(job_xml, name):
    assert job_xml(name).strip(), f"{name}.xml missing or empty"
    ET.fromstring(job_xml(name))


@pytest.mark.parametrize("name", JOBS)
def test_is_freestyle_project(job_xml, name):
    root = ET.fromstring(job_xml(name))
    assert root.tag == "project", "must be a freestyle job (<project> root)"
    assert "workflow-job" not in job_xml(name)


@pytest.mark.parametrize("name", JOBS)
def test_targets_ios_m4(job_xml, name):
    root = ET.fromstring(job_xml(name))
    assert root.findtext("assignedNode") == "ios-m4"


@pytest.mark.parametrize("name", JOBS)
def test_no_scm(job_xml, name):
    root = ET.fromstring(job_xml(name))
    scm = root.find("scm")
    assert scm is not None and scm.get("class", "").endswith("NullSCM"), \
        "agent cannot reach github (no DNS) — job must be SCM-less"


@pytest.mark.parametrize("name", JOBS)
def test_uses_dummy_credentials(job_xml, name):
    xml = job_xml(name)
    assert "ios-dummy-p12" in xml
    assert "ios-dummy-p12-pass" in xml
    assert "P12_FILE" in xml and "P12_PASS" in xml


@pytest.mark.parametrize("name", JOBS)
def test_embedded_script_matches_canonical(job_xml, name):
    script = JOBS[name]
    canonical = (JK / "ios" / "scripts" / script).read_text()
    embedded = extract_embedded_script(job_xml(name), script)
    assert embedded == canonical, f"job {name} embeds a stale/different {script}"


@pytest.mark.parametrize("name", JOBS)
def test_embedded_script_is_valid_bash(job_xml, name):
    script = JOBS[name]
    embedded = extract_embedded_script(job_xml(name), script)
    r = subprocess.run(["bash", "-n"], input=embedded, capture_output=True, text=True)
    assert r.returncode == 0, f"{script} fails bash -n: {r.stderr}"


@pytest.mark.parametrize("name", JOBS)
def test_script_has_hardcoded_variables(job_xml, name):
    embedded = extract_embedded_script(job_xml(name), JOBS[name])
    assert "KC_PASS=" in embedded
    assert "VARIANT=" in embedded
    assert "TARGET=" in embedded
    assert "SIGNING_HASH=" in embedded


@pytest.mark.parametrize("name,expect", [("codesign-fixed", True), ("codesign-err", False)])
def test_partition_list_applied_in_fixed_only(job_xml, name, expect):
    """The fixed script INVOKES set-key-partition-list; the err script only
    mentions it in a comment explaining it is deliberately omitted."""
    embedded = extract_embedded_script(job_xml(name), JOBS[name])
    invoked_lines = [
        ln for ln in embedded.splitlines()
        if "security set-key-partition-list" in ln and not ln.lstrip().startswith("#")
    ]
    has = len(invoked_lines) > 0
    assert has is expect, f"{name}: set-key-partition-list invocation should be {expect}"


@pytest.mark.parametrize("name", JOBS)
def test_script_cleanup_present(job_xml, name):
    embedded = extract_embedded_script(job_xml(name), JOBS[name])
    assert "create-keychain" in embedded
    assert "delete-keychain" in embedded


def test_err_script_has_watchdog():
    script = (JK / "ios" / "scripts" / "codesign-err.sh").read_text()
    assert "WATCHDOG_TIMEOUT" in script
    assert "kill -9" in script
    assert "sleep" in script
