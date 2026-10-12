#!/usr/bin/env python3
"""Adapt shared contracts into native SwiftPM resources without tracking test copies."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
ASSETS = {
    "shared/assets/TagData.json": "macos/Sources/MemoizeCore/Resources/TagData.json",
}
FIXTURES = {
    "shared/fixtures/memos-tags.json": "macos/Tests/MemoizeCoreTests/Fixtures/memos-tags.json",
    "shared/fixtures/search-parity.json": "macos/Tests/MemoizeCoreTests/Fixtures/search-parity.json",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="check shipped copies and canonical fixture validity")
    mode.add_argument("--prepare-tests", action="store_true", help="generate native test resources only")
    args = parser.parse_args()
    stale = []
    for canonical in FIXTURES:
        json.loads((ROOT / canonical).read_text())
    copies = ASSETS if args.check else FIXTURES if args.prepare_tests else ASSETS | FIXTURES
    for canonical, native in copies.items():
        source = (ROOT / canonical).read_bytes()
        destination = ROOT / native
        if destination.is_file() and destination.read_bytes() == source:
            continue
        if args.check:
            stale.append(native)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source)
            print(f"Adapted {canonical} -> {native}")
    if stale:
        print("Stale native resources: " + ", ".join(stale), file=sys.stderr)
        print("Run python3 tools/fixtures/sync.py and commit the shipped resource copies.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
