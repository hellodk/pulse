#!/usr/bin/env python3
"""
parse_profiles.py — Decode and extract metadata from .mobileprovision files.

Outputs a JSON array to --output. Each element contains:
  file, name, uuid, team_id, team_name, bundle_id, expiry (ISO-8601),
  days_left, distribution_type, device_count, provisions_all_devices,
  push_enabled, push_env, already_installed, error (if decode failed)
"""

import argparse
import glob
import json
import os
import plistlib
import subprocess
import sys
from datetime import datetime, timezone


def decode_profile(path: str) -> bytes:
    result = subprocess.run(
        ["security", "cms", "-D", "-i", path],
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode(errors="replace").strip())
    return result.stdout


def distribution_type(plist: dict) -> str:
    if plist.get("ProvisionsAllDevices", False):
        return "Enterprise"
    devices = plist.get("ProvisionedDevices") or []
    if devices:
        if plist.get("Entitlements", {}).get("get-task-allow", False):
            return "Development"
        return "AdHoc"
    return "AppStore"


def days_left(expiry) -> int | None:
    if expiry is None:
        return None
    if not hasattr(expiry, "tzinfo") or expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=timezone.utc)
    delta = expiry.astimezone(timezone.utc) - datetime.now(timezone.utc)
    return delta.days


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile-dir", required=True)
    ap.add_argument("--install-dir", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    install_dir = os.path.expanduser(args.install_dir)
    profiles = []

    pattern = os.path.join(args.profile_dir, "*.mobileprovision")
    files = sorted(glob.glob(pattern))
    if not files:
        print(f"ERROR: no .mobileprovision files found in {args.profile_dir}", file=sys.stderr)
        sys.exit(1)

    for path in files:
        entry: dict = {"file": os.path.basename(path)}
        try:
            raw = decode_profile(path)
            plist = plistlib.loads(raw)
        except Exception as exc:
            entry["error"] = str(exc)
            profiles.append(entry)
            continue

        expiry = plist.get("ExpirationDate")
        uuid = plist.get("UUID", "UNKNOWN")
        entitlements = plist.get("Entitlements") or {}
        app_id = entitlements.get("application-identifier", "")
        # Strip team prefix: "ABCD1234.com.example.app" → "com.example.app"
        bundle_id = app_id.split(".", 1)[-1] if "." in app_id else app_id

        installed_path = os.path.join(install_dir, f"{uuid}.mobileprovision")

        entry.update(
            {
                "name": plist.get("Name", "Unknown"),
                "uuid": uuid,
                "team_id": (plist.get("TeamIdentifier") or [""])[0],
                "team_name": plist.get("TeamName", ""),
                "bundle_id": bundle_id,
                "expiry": expiry.isoformat() if expiry else "Unknown",
                "days_left": days_left(expiry),
                "distribution_type": distribution_type(plist),
                "device_count": len(plist.get("ProvisionedDevices") or []),
                "provisions_all_devices": plist.get("ProvisionsAllDevices", False),
                "push_enabled": "aps-environment" in entitlements,
                "push_env": entitlements.get("aps-environment", ""),
                "already_installed": os.path.isfile(installed_path),
            }
        )
        profiles.append(entry)

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w") as fh:
        json.dump(profiles, fh, indent=2)

    print(f"Parsed {len(profiles)} profile(s) → {args.output}")


if __name__ == "__main__":
    main()
