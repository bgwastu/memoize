#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:?Usage: Scripts/release.sh 0.1.0}"
# build.sh validates the version before it is used in paths or release URLs.
Scripts/build.sh release "$version"
rm -rf build/release
mkdir -p build/release
/usr/bin/ditto -c -k --sequesterRsrc --keepParent build/Memoize.app "build/release/Memoize-$version.zip"
args=(--maximum-deltas 0 --download-url-prefix "https://github.com/bgwastu/memoize/releases/download/v$version/")
if [[ -n "${SPARKLE_PRIVATE_KEY:-}" ]]; then
    # CI supplies the one secret over stdin, never as a command argument or file.
    printf '%s' "$SPARKLE_PRIVATE_KEY" | .build/artifacts/sparkle/Sparkle/bin/generate_appcast --ed-key-file - "${args[@]}" build/release
else
    .build/artifacts/sparkle/Sparkle/bin/generate_appcast --account net.wastu.memoize.updates "${args[@]}" build/release
fi
/usr/bin/swift -module-cache-path .build/clang-cache Scripts/verify-release.swift build/Memoize.app build/release
printf 'Prepared %s locally. Nothing has been published.\n' "$version"
