#!/usr/bin/env bash
set -euo pipefail

ANGLE_COMMIT=4cb3bebdc3b96723bb501328afe50f4f5cbc1de3 # branch chromium/8074
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/../../.cache/angle}"
ANGLE_WORK="${ANGLE_WORK:-$HOME/.cache/libretrodroid/angle}"
JOBS="${JOBS:-3}"
SIMULATOR="${SIMULATOR:-1}"

mkdir -p "$ANGLE_WORK" "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ANGLE_WORK"

if [ ! -d depot_tools ]; then
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
# depot_tools finds itself from the path it is invoked with, and cipd runs from another directory: a relative
# path made it miss cipd_client_version.digests ("mac-arm64 is not supported"). Always use absolute paths.
DEPOT_TOOLS="$ANGLE_WORK/depot_tools"
export PATH="$DEPOT_TOOLS:$PATH"
export DEPOT_TOOLS_UPDATE=0
[ -f "$DEPOT_TOOLS/python3_bin_reldir.txt" ] || "$DEPOT_TOOLS/ensure_bootstrap"

if [ ! -d angle/.git ]; then
  git clone https://chromium.googlesource.com/angle/angle
fi
cd angle
if [ "$(git rev-parse HEAD)" != "$ANGLE_COMMIT" ]; then
  git fetch origin "$ANGLE_COMMIT"
  git checkout --detach "$ANGLE_COMMIT"
fi
if [ ! -f ../.gclient ] && [ ! -f .gclient ]; then
  python3 scripts/bootstrap.py
fi
grep -q "target_os" .gclient || echo "target_os = ['ios']" >> .gclient
gclient sync --no-history --shallow
# ANGLE's own depot_tools is an empty submodule until the sync fills it.
if [ -x third_party/depot_tools/ensure_bootstrap ] && [ ! -f third_party/depot_tools/python3_bin_reldir.txt ]; then
  "$PWD/third_party/depot_tools/ensure_bootstrap"
fi

build() { # $1 = pasta, $2 = device|simulator
  gn gen "out/$1" --args="
    target_os=\"ios\" target_cpu=\"arm64\" target_environment=\"$2\"
    is_debug=false is_component_build=false symbol_level=0
    ios_enable_code_signing=false ios_deployment_target=\"15.0\"
    angle_enable_metal=true angle_enable_gl=false angle_enable_vulkan=false
    angle_enable_swiftshader=false angle_enable_null=false
    angle_build_tests=false angle_has_frame_capture=false angle_enable_wgpu=false"
  ninja -j "$JOBS" -C "out/$1" libEGL libGLESv2
}
build ios-device device
if [ "$SIMULATOR" = 1 ]; then build ios-simulator simulator; fi

for lib in libEGL libGLESv2; do
  rm -rf "$OUT/$lib.xcframework"
  slices=(-framework "out/ios-device/$lib.framework")
  if [ "$SIMULATOR" = 1 ]; then slices+=(-framework "out/ios-simulator/$lib.framework"); fi
  xcodebuild -create-xcframework "${slices[@]}" -output "$OUT/$lib.xcframework"
done
rm -rf "$OUT/include" && cp -R include "$OUT/include"
echo "$ANGLE_COMMIT" > "$OUT/COMMIT"
echo "ANGLE ready in $OUT"
