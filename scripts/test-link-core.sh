#!/usr/bin/env bash
# Runs an actual libretro core through the engine Netpacket bridge.
# Build the selected core first; pass its shared library and a diagnostic ROM.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${1:?usage: test-link-core.sh core.dylib diagnostic.gba}"
ROM="${2:?usage: test-link-core.sh core.dylib diagnostic.gba}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
set --
if [ "$(uname -s)" = Linux ]; then set -- -ldl; fi
c++ -std=c++17 "$ROOT/native/tests/netpacket_core_test.cpp" \
    "$ROOT/native/src/netpacket.cpp" "$@" -o "$OUT/netpacket-core"
"$OUT/netpacket-core" "$CORE" "$ROM"
