#!/usr/bin/env python3
"""Materialize CI signing input in a private temporary file, never command args."""

import base64
import os
from pathlib import Path


def main():
    for name in ('ANDROID_KEYSTORE_BASE64', 'MEMOIZE_ANDROID_KEYSTORE_PASSWORD', 'MEMOIZE_ANDROID_KEY_ALIAS', 'MEMOIZE_ANDROID_KEY_PASSWORD', 'MEMOIZE_ANDROID_CERTIFICATE_SHA256'):
        if not os.environ.get(name):
            raise ValueError(f'Required Android release identity is missing: {name}')
    path = Path(os.environ['RUNNER_TEMP']) / 'memoize-production-signing.keystore'
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True))
    with Path(os.environ['GITHUB_ENV']).open('a') as output:
        output.write(f'MEMOIZE_ANDROID_KEYSTORE={path}\n')


if __name__ == '__main__':
    main()
