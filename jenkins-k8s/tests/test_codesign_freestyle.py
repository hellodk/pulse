"""Artifact tests for the freestyle codesign partition-list experiment pipelines.

Same experiment as the inline-pipeline variants (issue #4), but as two
FREESTYLE PROJECTS whose build step executes the sign shell directly.
The ONLY difference between them is whether
`security set-key-partition-list` is applied after the p12 import.

Run:  pytest jenkins-k8s/tests/test_codesign_freestyle.py -q
"""

from pathlib import Path
import xml.etree.ElementTree as ET

import pytest

JK = Path(__file__).resolve().parents[1]

VARIANTS = {
    "codesign-ls-nopartition": False,
    "codesign-ls-partition": True,
}


@pytest.fixture(scope="module")
def job_xml(request):
    def _load(name: str) -> str:
        return (JK / "ios" / "job-configs" / "freestyle" / f"{name}.xml").read_text()
    return _load


@pytest.mark.parametrize("name", VARIANTS)
def test_job_xml_exists(job_xml, name):
    assert job_xml(name).strip(), f"{name}.xml missing or empty"


@pytest.mark.parametrize("name", VARIANTS)
def test_is_freestyle_project(job_xml, name):
    root = ET.fromstring(job_xml(name))
    assert root.tag == "project", "freestyle job must have <project> root"
    assert "workflow-job" not in job_xml(name), "must NOT be a pipeline/flow-definition"


@pytest.mark.parametrize("name", VARIANTS)
def test_single_shell_builder(job_xml, name):
    root = ET.fromstring(job_xml(name))
    builders = root.find("builders")
    assert builders is not None
    shells = builders.findall("hudson.tasks.Shell")
    assert len(shells) == 1, "expected exactly one shell build step"


@pytest.mark.parametrize("name", VARIANTS)
def test_targets_ios_m4_label(job_xml, name):
    root = ET.fromstring(job_xml(name))
    assert root.findtext("assignedNode") == "ios-m4"


@pytest.mark.parametrize("name", VARIANTS)
def test_uses_credential_binding_wrapper(job_xml, name):
    """Freestyle has no withCredentials step; credentials are bound in buildWrappers."""
    xml = job_xml(name)
    assert "SecretBuildWrapper" in xml
    assert "FileBinding" in xml and "P12_FILE" in xml
    assert "StringBinding" in xml and "P12_PASS" in xml
    assert "ios-dummy-p12" in xml
    assert "ios-dummy-p12-pass" in xml


@pytest.mark.parametrize("name", VARIANTS)
def test_signs_copied_ls_binary(job_xml, name):
    xml = job_xml(name)
    assert "/bin/ls" in xml, "must sign a copy of /bin/ls"


@pytest.mark.parametrize("name", VARIANTS)
def test_partition_list_presence(job_xml, name):
    """The partition command must be INVOKED in exactly one variant."""
    xml = job_xml(name)
    has_partition = "security set-key-partition-list" in xml
    assert has_partition is VARIANTS[name], (
        f"{name}: set-key-partition-list presence should be {VARIANTS[name]}"
    )


@pytest.mark.parametrize("name", VARIANTS)
def test_ephemeral_keychain_cleanup(job_xml, name):
    xml = job_xml(name)
    assert "create-keychain" in xml
    assert "delete-keychain" in xml, "keychain must be cleaned up"


@pytest.mark.parametrize("name", VARIANTS)
def test_timestamp_none_and_explicit_keychain(job_xml, name):
    xml = job_xml(name)
    assert "--timestamp=none" in xml
    assert "--keychain" in xml


@pytest.mark.parametrize("name", VARIANTS)
def test_shell_syntax_is_valid_bash(job_xml, name):
    import subprocess
    root = ET.fromstring(job_xml(name))
    cmd = root.findtext("builders/hudson.tasks.Shell/command")
    assert cmd, "shell command missing"
    r = subprocess.run(["bash", "-n"], input=cmd, capture_output=True, text=True)
    assert r.returncode == 0, f"shell command fails bash -n: {r.stderr}"
