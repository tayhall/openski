#!/usr/bin/env bash
# Builds and runs the host tests whose --check mode guards a committed Android fixture, so a firmware change
# that drifts from the fixture is caught: run after any change under lib/Motion/src. Needs g++ on PATH (on the
# Windows dev machine, the WinLibs mingw64/bin from winget).
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
build() { g++ -std=gnu++17 -Ilib/Motion/src "tools/$1.cpp" -o "$out/$1"; }
build test_rest_bias
"$out/test_rest_bias"
"$out/test_rest_bias" --check android/app/src/test/resources/rest_bias.txt
build test_heading_tracker
"$out/test_heading_tracker"
"$out/test_heading_tracker" --check android/app/src/test/resources/heading_turns.txt
echo "fixtures match the firmware"
