#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
for test in netplay_state_hash state_hash_worker netplay_generation netpacket rewind audio_fpssync system_ram; do
  if [ "$test" = netplay_generation ]; then
    c++ -std=c++17 -pthread "$ROOT/native/tests/${test}_test.cpp" "$ROOT/native/src/netplay.cpp" -o "$OUT/$test"
  elif [ "$test" = netpacket ]; then
    c++ -std=c++17 -pthread "$ROOT/native/tests/netpacket_test.cpp" "$ROOT/native/src/netpacket.cpp" -o "$OUT/$test"
  elif [ "$test" = audio_fpssync ]; then
    c++ -std=c++17 -pthread "$ROOT/native/tests/audio_fpssync_test.cpp" "$ROOT/native/src/audio.cpp" "$ROOT/native/src/fpssync.cpp" -o "$OUT/$test"
  elif [ "$test" = rewind ]; then
    c++ -std=c++17 -O2 "$ROOT/native/tests/rewind_test.cpp" -o "$OUT/$test"
  else
    c++ -std=c++17 -pthread "$ROOT/native/tests/${test}_test.cpp" -o "$OUT/$test"
  fi
  "$OUT/$test"
done
