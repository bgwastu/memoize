#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/screen-share-probe
export CLANG_MODULE_CACHE_PATH="$PWD/.build/clang-cache"
/usr/bin/swiftc -O -parse-as-library -swift-version 6 \
    Scripts/WindowCaptureExclusion.swift Scripts/CaptureProbePanel.swift Scripts/ScreenShareProbe.swift \
    -o .build/screen-share-probe/probe
.build/screen-share-probe/probe "$@"
