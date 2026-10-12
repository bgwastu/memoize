#!/usr/bin/env python3
"""Stage the signed APK and metadata consumed by the Android updater."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

version = os.environ["VERSION_NAME"]
code = int(os.environ["VERSION_CODE"])
source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
out = Path("android/build/release")
out.mkdir(parents=True, exist_ok=True)
apk = out / f"Memoize-{version}.apk"
shutil.copy2("android/app/build/outputs/apk/release/app-release.apk", apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
metadata = {"schema": 1, "repository": "bgwastu/memoize", "platform": "android",
            "tag": f"android/v{version}", "version_name": version, "version_code": code,
            "source_sha": source, "assets": {apk.name: digest},
            "signing_certificate_sha256": os.environ["MEMOIZE_ANDROID_CERTIFICATE_SHA256"]}
(out / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")
(out / "SHA256SUMS").write_text(f"{digest}  {apk.name}\n")
