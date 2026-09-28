#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
for test in netplay_state_hash state_hash_worker netplay_generation netpacket; do
  if [ "$test" = netplay_generation ]; then
    c++ -std=c++17 -pthread "$ROOT/native/tests/${test}_test.cpp" "$ROOT/native/src/netplay.cpp" -o "$OUT/$test"
  elif [ "$test" = netpacket ]; then
    c++ -std=c++17 -pthread "$ROOT/native/tests/netpacket_test.cpp" "$ROOT/native/src/netpacket.cpp" -o "$OUT/$test"
  else
    c++ -std=c++17 -pthread "$ROOT/native/tests/${test}_test.cpp" -o "$OUT/$test"
  fi
  "$OUT/$test"
done
