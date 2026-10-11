#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

app="$PWD/build/Memoize.app/Contents/MacOS/Memoize"
test_root="$PWD/.build/presentation-browser-test"
background="$test_root/background"
[[ -x "$app" ]] || { echo 'Build Memoize first with Scripts/build.sh debug.' >&2; exit 1; }
[[ -f "$test_root/node_modules/playwright/index.mjs" ]] || {
    echo 'Install the test dependency: npm install --prefix .build/presentation-browser-test --no-audit --no-fund playwright' >&2
    exit 1
}
if [[ ! -x "$background" || Scripts/PresentationBrowserBackground.swift -nt "$background" ]]; then
    /usr/bin/swiftc -O -parse-as-library -swift-version 6 Scripts/PresentationBrowserBackground.swift -o "$background"
fi
run_directory="$(mktemp -d "$test_root/run.XXXXXX")"
native_pid=''
background_pid=''
cleanup() {
    printf 'finish' > "$run_directory/browser-command.txt"
    touch "$run_directory/background-stop"
    if [[ -n "$native_pid" ]]; then wait "$native_pid" || true; fi
    if [[ -n "$background_pid" ]]; then wait "$background_pid" || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# All native notes belong to a new test store; capture permission must already
# exist. The diagnostic never requests it or opens the user's saved library.
MEMOIZE_DATA_DIR="$run_directory" MEMOIZE_SMOKE_BROWSER_SHARING=1 "$app" --smoke-test > "$run_directory/native.log" 2>&1 &
native_pid=$!
for ((attempt=0; attempt<160; attempt++)); do
    [[ -f "$run_directory/browser-state.json" ]] && break
    if ! kill -0 "$native_pid" 2>/dev/null; then cat "$run_directory/native.log"; exit 1; fi
    sleep 0.05
done
[[ -f "$run_directory/browser-state.json" ]] || { cat "$run_directory/native.log"; echo 'Native presentation did not start.' >&2; exit 1; }
"$background" "$run_directory/background-stop" &
background_pid=$!
sleep 0.5
node Scripts/test-presentation-browser.mjs "$run_directory"
wait "$native_pid"
native_pid=''
printf 'Synthetic test artifacts: %s\n' "$run_directory"
