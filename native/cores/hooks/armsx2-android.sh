#!/usr/bin/env bash
# ARMSX2 takes png, jpeg, SDL, shaderc and the rest from a prefix its own script builds for the NDK, once per ABI.
# Called by build-cores.sh with NDK, ABI, API, SRC and DEPS; prints the CMake arguments that point at the prefix.
set -euo pipefail
if [ ! -f "$DEPS/lib/libshaderc_combined.a" ]; then
  mkdir -p "$DEPS" "$DEPS.work"
  (cd "$DEPS.work" && ANDROID_NDK="$NDK" ANDROID_ABI="$ABI" ANDROID_API="$API" \
    bash "$SRC/.github/workflows/scripts/android/build-dependencies.sh" "$DEPS") >&2
fi
echo "-DCMAKE_PREFIX_PATH=$DEPS -DCMAKE_FIND_ROOT_PATH=$DEPS"
