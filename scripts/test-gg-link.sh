#!/usr/bin/env bash
# Two patched Genesis Plus GX link cores on this Mac, joined by an in-memory Netpacket cable, run a
# Gear-to-Gear test ROM (generated here, CC0): serial bytes with the receive NMI, parallel echoes with
# the PC6 NMI, a partner that joins late and one that leaves.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${CORES_WORK:-$HOME/.cache/libretrodroid/cores}/genesis_plus_gx_link-host"
OUT="$ROOT/build/native-tests/gg-link"
line="$(grep '^genesis_plus_gx_link|' "$ROOT/native/cores/cores.properties")"
IFS='|' read -r _ repo commit _ <<<"$line"
[ -d "$WORK/.git" ] || git clone --quiet "$repo" "$WORK"
git -C "$WORK" fetch --quiet origin "$commit" 2>/dev/null || true
git -C "$WORK" checkout --quiet --force --detach "$commit"
git -C "$WORK" clean -qfdx
for patch in "$ROOT/native/cores/patches/genesis_plus_gx_link"/*.patch; do git -C "$WORK" apply "$patch"; done
make -C "$WORK" -f Makefile.libretro platform=osx -j"${JOBS:-3}" >/dev/null 2>&1
mkdir -p "$OUT"
cp "$WORK/genesis_plus_gx_libretro.dylib" "$OUT/a.dylib"
cp "$WORK/genesis_plus_gx_libretro.dylib" "$OUT/b.dylib" # A second file: a second instance of the core's globals.
python3 "$ROOT/native/tests/link/make_gg_link_rom.py" "$OUT/linktest.gg" >/dev/null
c++ -std=c++17 "$ROOT/native/tests/link/gg_link_test.cpp" -o "$OUT/gg-link"
"$OUT/gg-link" "$OUT/a.dylib" "$OUT/b.dylib" "$OUT/linktest.gg"
