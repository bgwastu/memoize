#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

configuration="${1:-release}"
version="${2:-$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' Resources/Info.plist)}"
[[ "$configuration" == debug || "$configuration" == release ]] || { echo 'Use debug or release.' >&2; exit 2; }
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Use a version such as 0.1.0.' >&2; exit 2; }

export CLANG_MODULE_CACHE_PATH="$PWD/.build/clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$PWD/.build/module-cache"
# Xcode's resource accessors find bundles in Contents/Resources on every supported toolchain.
/usr/bin/swift build --build-system xcode --disable-sandbox --cache-path .build/cache -c "$configuration" --product Memoize
bin="$(/usr/bin/swift build --build-system xcode --disable-sandbox --cache-path .build/cache -c "$configuration" --show-bin-path)"
app="$PWD/build/Memoize.app"
# Rebuild the bundle so stale resources and test fixtures never ship.
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$app/Contents/Frameworks"
cp "$bin/Memoize" "$app/Contents/MacOS/"
cp Resources/Info.plist "$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set CFBundleShortVersionString $version" "$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set CFBundleVersion $version" "$app/Contents/Info.plist"
for name in Memoize_MemoizeCore GRDB_GRDB KeyboardShortcuts_KeyboardShortcuts; do
    /usr/bin/ditto "$bin/$name.bundle" "$app/Contents/Resources/$name.bundle"
done
/usr/bin/ditto .build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework "$app/Contents/Frameworks/Sparkle.framework"
for notice in .build/checkouts/GRDB.swift/LICENSE .build/checkouts/KeyboardShortcuts/license \
    .build/checkouts/swift-markdown/LICENSE.txt .build/checkouts/swift-markdown/NOTICE.txt \
    .build/checkouts/swift-cmark/COPYING .build/artifacts/sparkle/Sparkle/LICENSE; do
    printf '\n--- %s ---\n\n' "$notice"
    cat "$notice"
done > "$app/Contents/Resources/THIRD_PARTY_NOTICES.txt"
cp LICENSE "$app/Contents/Resources/LICENSE.txt"
if [[ ! -f build/Memoize.icns || Scripts/make-icon.swift -nt build/Memoize.icns ]]; then
    /usr/bin/swift Scripts/make-icon.swift build/Memoize.iconset
    /usr/bin/iconutil -c icns build/Memoize.iconset -o build/Memoize.icns
fi
cp build/Memoize.icns "$app/Contents/Resources/"
/usr/bin/codesign --force --sign - "$app"
/usr/bin/codesign --verify --deep --strict "$app"
printf '%s\n' "$app"
