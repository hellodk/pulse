"""Artifact tests for the codesign partition-list experiment pipelines.

Two inline-pipeline jobs on jenkins-b codesign a copy of /bin/ls with the
dummy identity; the ONLY difference between them is whether
`security set-key-partition-list` is applied after the p12 import.
"""

from pathlib import Path

import pytest

JK = Path(__file__).resolve().parents[1]

VARIANTS = {
    "codesign-ls-nopartition": False,
    "codesign-ls-partition": True,
}


@pytest.fixture(scope="module")
def job_xml(request):
    def _load(name: str) -> str:
        return (JK / "ios" / "job-configs" / f"{name}.xml").read_text()
    return _load


@pytest.mark.parametrize("name", VARIANTS)
def test_job_xml_exists(job_xml, name):
    assert job_xml(name).strip(), f"{name}.xml missing or empty"


@pytest.mark.parametrize("name", VARIANTS)
def test_inline_pipeline_definition(job_xml, name):
    xml = job_xml(name)
    assert "CpsFlowDefinition" in xml, "must be an inline pipeline"
    assert "<sandbox>true</sandbox>" in xml


@pytest.mark.parametrize("name", VARIANTS)
def test_targets_ios_m4(job_xml, name):
    assert "node('ios-m4')" in job_xml(name)


@pytest.mark.parametrize("name", VARIANTS)
def test_uses_dummy_credentials(job_xml, name):
    xml = job_xml(name)
    assert "ios-dummy-p12-pass" in xml
    assert "ios-dummy-p12'" in xml or 'ios-dummy-p12"' in xml or "ios-dummy-p12," in xml


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
