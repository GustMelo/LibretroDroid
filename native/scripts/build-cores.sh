#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:?uso: build-cores.sh android|ios [core...]}"
shift
ONLY=" $* "
HERE="$(cd "$(dirname "$0")" && pwd)"
CORES="$HERE/../cores"
OUT="${OUT:-$HERE/../../.cache/cores}"
WORK="${CORES_WORK:-$HOME/.cache/libretrodroid/cores}"
JOBS="${JOBS:-3}"
SIMULATOR="${SIMULATOR:-1}"
IOS_MIN="15.0"

mkdir -p "$OUT" "$WORK"
OUT="$(cd "$OUT" && pwd)"

sdk_dir() { echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"; }
cmake_bin() { ls -d "$(sdk_dir)"/cmake/*/bin | sort -V | tail -1; }
ndk_dir() {
  local ndk
  ndk="$(ls -d "$(sdk_dir)"/ndk/* | sort -V | tail -1)"
  [ -x "$ndk/ndk-build" ] || { echo "NDK not found in $(sdk_dir)/ndk" >&2; exit 1; }
  echo "$ndk"
}

checkout() { # core, repo, commit
  local src="$WORK/$1"
  [ -d "$src/.git" ] || git clone --quiet "$2" "$src"
  if ! git -C "$src" cat-file -e "$3^{commit}" 2>/dev/null; then
    git -C "$src" fetch --quiet origin "$3" || git -C "$src" fetch --quiet --unshallow origin || git -C "$src" fetch --quiet origin
  fi
  git -C "$src" checkout --quiet --force --detach "$3"
  git -C "$src" clean -qfdx >/dev/null
  for patch in "$CORES/patches/$1"/*.patch; do
    [ -f "$patch" ] && git -C "$src" apply "$patch"
  done
  return 0
}

fingerprint() { # core, commit, build, extra
  { echo "$2|$3|$4"; cat "$CORES/patches/$1"/*.patch 2>/dev/null || true; } | shasum | cut -c1-16
}

build_android() { # core, src, key, build, jni
  local target="$OUT/android/arm64-v8a/lib$1_libretro_android.so" stamp="$OUT/android/arm64-v8a/.$1.build"
  [ -f "$target" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$3" ] && return
  mkdir -p "$(dirname "$target")"
  if [[ "$4" == cmake:* ]]; then
    local build="$WORK/build/$1-android"
    "$(cmake_bin)/cmake" -S "$2" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$(cmake_bin)/ninja" \
      -DCMAKE_TOOLCHAIN_FILE="$(ndk_dir)/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release ${4#cmake:}
    "$(cmake_bin)/ninja" -C "$build" -j "$JOBS"
    # A fork may keep its upstream library name (mgba_link builds mgba_libretro).
    cp "$(find "$build" -name "*_libretro*.so" | head -1)" "$target"
    "$(ls "$(ndk_dir)"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)" --strip-unneeded "$target"
  else
    rm -rf "$WORK/libs/$1" "$WORK/obj/$1"
    "$(ndk_dir)/ndk-build" -C "$2/$5" -j"$JOBS" APP_ABI=arm64-v8a APP_PLATFORM=android-29 \
      NDK_OUT="$WORK/obj/$1" NDK_LIBS_OUT="$WORK/libs/$1"
    cp "$(find "$WORK/libs/$1/arm64-v8a" -name "*.so" | head -1)" "$target"
  fi
  echo "$3" > "$stamp"
}

build_ios_slice() { # core, src, build, extra, sdk(iphoneos|iphonesimulator)
  local name="$1_libretro" sdk_path dylib min_flag platform_name
  sdk_path="$(xcrun --sdk "$5" --show-sdk-path)"
  if [ "$5" = iphoneos ]; then
    min_flag="-miphoneos-version-min=$IOS_MIN"; platform_name=iPhoneOS
  else
    min_flag="-mios-simulator-version-min=$IOS_MIN"; platform_name=iPhoneSimulator
  fi
  if [[ "$3" == cmake:* ]]; then
    local build="$WORK/build/$1-$5"
    "$(cmake_bin)/cmake" -S "$2" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$(cmake_bin)/ninja" \
      -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
      -DCMAKE_OSX_SYSROOT="$sdk_path" -DCMAKE_BUILD_TYPE=Release ${3#cmake:}
    "$(cmake_bin)/ninja" -C "$build" -j "$JOBS"
    dylib="$(find "$build" -name "*_libretro*.dylib" | head -1)"
  else
    local dir="$2/$(dirname "$3")" makefile
    makefile="$(basename "$3")"
    git -C "$2" clean -qfdx >/dev/null
    make -C "$dir" -f "$makefile" -j"$JOBS" platform=ios-arm64 IOSSDK="$sdk_path" MINVERSION="$min_flag" $4
    dylib="$(ls "$dir"/*_libretro_ios.dylib | head -1)"
  fi

  local fw="$WORK/ios/$5/$name.framework"
  rm -rf "$fw" && mkdir -p "$fw"
  cp "$dylib" "$fw/$name"
  install_name_tool -id "@rpath/$name.framework/$name" "$fw/$name"
  cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>$name</string>
  <key>CFBundleIdentifier</key><string>com.libretrodroid.core.${1//_/-}</string>
  <key>CFBundleName</key><string>$name</string>
  <key>CFBundlePackageType</key><string>FMWK</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleSupportedPlatforms</key><array><string>$platform_name</string></array>
  <key>MinimumOSVersion</key><string>$IOS_MIN</string>
</dict></plist>
PLIST
}

build_ios() { # core, src, key, build, extra
  local name="$1_libretro" stamp="$OUT/ios/.$1.build" key="$3-sim$SIMULATOR"
  [ -d "$OUT/ios/$name.xcframework" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$key" ] && return
  local frameworks=()
  build_ios_slice "$1" "$2" "$4" "$5" iphoneos
  frameworks+=(-framework "$WORK/ios/iphoneos/$name.framework")
  if [ "$SIMULATOR" = 1 ]; then
    build_ios_slice "$1" "$2" "$4" "$5" iphonesimulator
    frameworks+=(-framework "$WORK/ios/iphonesimulator/$name.framework")
  fi
  mkdir -p "$OUT/ios"
  rm -rf "$OUT/ios/$name.xcframework"
  xcodebuild -create-xcframework "${frameworks[@]}" -output "$OUT/ios/$name.xcframework" >/dev/null
  echo "$key" > "$stamp"
}

grep -v '^\s*#' "$CORES/cores.properties" | grep -v '^\s*$' | while IFS='|' read -r core repo commit build jni ios_extra; do
  [ "$ONLY" = "  " ] || [[ "$ONLY" == *" $core "* ]] || continue
  echo "core $core @ ${commit:0:7} ($PLATFORM)"
  checkout "$core" "$repo" "$commit"
  key="$(fingerprint "$core" "$commit" "$build" "$ios_extra")"
  case "$PLATFORM" in
    android) build_android "$core" "$WORK/$core" "$key" "$build" "$jni" ;;
    ios) build_ios "$core" "$WORK/$core" "$key" "$build" "$ios_extra" ;;
    *) echo "plataforma desconhecida: $PLATFORM" >&2; exit 1 ;;
  esac
done
