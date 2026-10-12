#!/usr/bin/env python3
"""Verify the installable APK identity, signer and universal SQLite ABIs."""

import argparse
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path


def verify(apk, name, code, certificate, application_id='net.wastu.memoize'):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    tools = sdk / 'build-tools' / '37.0.0'
    signed = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    # New apksigner versions label each signing scheme; repeated schemes must
    # still identify one certificate, while any different signer is rejected.
    fingerprints = {match.group(1).lower() for line in signed.splitlines()
                    if (match := re.fullmatch(r'(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})', line.strip()))}
    expected = certificate.replace(':', '').lower()
    if fingerprints != {expected}:
        raise ValueError('APK signer does not match the permanent expected certificate')
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (application_id, str(code), name):
        raise ValueError('APK application identity or version differs from source metadata')
    minimum_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", badging, re.MULTILINE)
    if not minimum_sdk or minimum_sdk.group(1) != '36':
        raise ValueError('APK minimum SDK must remain Android 16 / API 36')
    if "application-debuggable" in badging:
        raise ValueError('Release APK is debuggable')
    with zipfile.ZipFile(apk) as archive:
        libraries = {}
        for filename in archive.namelist():
            match = re.fullmatch(r'lib/([^/]+)/([^/]+\.so)', filename)
            if match:
                libraries.setdefault(match[1], set()).add(match[2])
        if not {'arm64-v8a', 'x86_64'}.issubset(libraries):
            raise ValueError('APK lacks universal modern-device ABIs')
        baseline = libraries['arm64-v8a']
        if not any('sqlite' in library.lower() for library in baseline) or any(value != baseline for value in libraries.values()):
            raise ValueError('Bundled SQLite/native libraries differ across supported ABIs')
    return {'application_id': application_id, 'version_name': name, 'version_code': code,
            'certificate_sha256': expected, 'abis': sorted(libraries)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--application-id', default='net.wastu.memoize')
    parser.add_argument('--certificate', default=os.environ.get('MEMOIZE_ANDROID_CERTIFICATE_SHA256'), required=not os.environ.get('MEMOIZE_ANDROID_CERTIFICATE_SHA256'))
    arguments = parser.parse_args()
    print(json.dumps(verify(arguments.apk, arguments.version_name, arguments.version_code, arguments.certificate,
                            arguments.application_id), sort_keys=True))


if __name__ == '__main__':
    main()
