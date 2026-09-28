#!/usr/bin/env bash
# Two patched Gambatte cores on this Mac, linked over 127.0.0.1, run a serial test ROM
# (generated here, CC0) and must each receive 16 correct bytes from the other.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${CORES_WORK:-$HOME/.cache/libretrodroid/cores}/gambatte-host"
OUT="$ROOT/build/native-tests/gb-link"
line="$(grep '^gambatte|' "$ROOT/native/cores/cores.properties")"
IFS='|' read -r _ repo commit _ <<<"$line"
[ -d "$WORK/.git" ] || git clone --quiet "$repo" "$WORK"
git -C "$WORK" fetch --quiet origin "$commit" 2>/dev/null || true
git -C "$WORK" checkout --quiet --force --detach "$commit"
git -C "$WORK" clean -qfdx
for patch in "$ROOT/native/cores/patches/gambatte"/*.patch; do git -C "$WORK" apply --whitespace=nowarn "$patch"; done
make -C "$WORK" -f Makefile.libretro platform=osx HAVE_NETWORK=1 -j"${JOBS:-3}" >/dev/null 2>&1
mkdir -p "$OUT"
cp "$WORK/gambatte_libretro.dylib" "$OUT/a.dylib"
cp "$WORK/gambatte_libretro.dylib" "$OUT/b.dylib" # A second file: a second instance of the core's globals.
python3 "$ROOT/native/tests/link/make_gb_link_rom.py" "$OUT/linktest.gb" >/dev/null
c++ -std=c++17 "$ROOT/native/tests/link/gb_link_test.cpp" -o "$OUT/gb-link"
"$OUT/gb-link" "$OUT/a.dylib" "$OUT/b.dylib" "$OUT/linktest.gb" 2>/dev/null
