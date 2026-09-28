#!/usr/bin/env bash
# retrolink: the patched mGBA core runs linked consoles itself. Builds it for this Mac and checks
# 2 Game Boys (serial ROM) and 2, 3 and 4 GBAs (Multi-Pak ROM), each with a second instance that
# joined from the first's state and must stay byte-identical.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${CORES_WORK:-$HOME/.cache/libretrodroid/cores}/mgba-host"
OUT="$ROOT/build/native-tests/retrolink"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if ! command -v cmake >/dev/null || ! command -v ninja >/dev/null; then
  PATH="$(ls -d "$SDK"/cmake/*/bin | sort -V | tail -1):$PATH"
fi
line="$(grep '^mgba|' "$ROOT/native/cores/cores.properties")"
IFS='|' read -r _ repo commit _ <<<"$line"
[ -d "$WORK/.git" ] || git clone --quiet "$repo" "$WORK"
git -C "$WORK" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$WORK" fetch --quiet origin "$commit"
git -C "$WORK" checkout --quiet --force --detach "$commit"
git -C "$WORK" clean -qfdx
for patch in "$ROOT/native/cores/patches/mgba"/*.patch; do git -C "$WORK" apply --whitespace=nowarn "$patch"; done
cmake -S "$WORK" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_LIBRETRO=ON -DBUILD_SDL=OFF -DBUILD_QT=OFF \
  -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EPOXY=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF \
  -DUSE_LZMA=OFF -DUSE_FFMPEG=OFF -DUSE_PNG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_LIBZIP=OFF -DSKIP_LIBRARY=ON \
  -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF >/dev/null
ninja -C "$WORK/build" -j"${JOBS:-3}" mgba_libretro >/dev/null
mkdir -p "$OUT"
rm -f "$OUT/a.dylib" "$OUT/b.dylib" # A copy over a loaded, signed dylib gets the process killed.
cp "$WORK/build/mgba_libretro.dylib" "$OUT/a.dylib"
cp "$WORK/build/mgba_libretro.dylib" "$OUT/b.dylib" # A second file: a second instance of the core's globals.
python3 "$ROOT/native/tests/link/make_gb_link_rom.py" "$OUT/link-gb.gb" >/dev/null
python3 "$ROOT/native/tests/link/make_gba_link4_rom.py" "$OUT/link-gba4.gba"
c++ -std=c++17 "$ROOT/native/tests/link/retrolink_test.cpp" -o "$OUT/retrolink-test"
run() { "$OUT/retrolink-test" "$OUT/a.dylib" "$OUT/b.dylib" "$@" 2>/dev/null; }
run "$OUT/link-gb.gb" 2 gb
for players in 2 3 4; do run "$OUT/link-gba4.gba" "$players" gba4; done
