#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${OUT:-$ROOT/.cache/engine}"
SIMULATOR="${SIMULATOR:-1}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
CMAKE_BIN="$(ls -d "$SDK"/cmake/*/bin | sort -V | tail -1)"
ANGLE_INCLUDE="${ANGLE_INCLUDE:-$ROOT/.cache/angle/include}"
[ -d "$ANGLE_INCLUDE" ] || ANGLE_INCLUDE="$HOME/.cache/libretrodroid/angle/angle/include"

build() { # fatia (ios-arm64|ios-simulator-arm64), sdk (iphoneos|iphonesimulator)
  local build="$OUT/build-$1"
  "$CMAKE_BIN/cmake" -S "$HERE/.." -B "$build" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$2" --show-sdk-path)" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANGLE_INCLUDE_DIR="$ANGLE_INCLUDE"
  "$CMAKE_BIN/ninja" -C "$build" -j "${JOBS:-3}" retroengine
  mkdir -p "$OUT/$1"
  cp "$build/libretroengine.a" "$OUT/$1/"
}

build ios-arm64 iphoneos
if [ "$SIMULATOR" = 1 ]; then build ios-simulator-arm64 iphonesimulator; fi
rm -rf "$OUT/include" && cp -R "$HERE/../bindings/c/include" "$OUT/include"
echo "motor iOS em $OUT"
