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
# 32-bit ARM is what most Android TV boxes run, even on 64-bit chips.
ANDROID_ABIS="${ANDROID_ABIS:-arm64-v8a armeabi-v7a}"
ANDROID_API=28

mkdir -p "$OUT" "$WORK"
OUT="$(cd "$OUT" && pwd)"

sdk_dir() { echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"; }
cmake_bin() { ls -d "$(sdk_dir)"/cmake/*/bin | sort -V | tail -1; }
# The NDK every core is built with unless its line in cores.properties names another (CORE_NDK): a new NDK
# installed for one core must not change the compiler of the rest. The newest one stands in when it is missing.
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"
CORE_NDK=""
ndk_dir() {
  local ndk="$(sdk_dir)/ndk/${CORE_NDK:-$ANDROID_NDK_VERSION}"
  [ -x "$ndk/ndk-build" ] || ndk="$(ls -d "$(sdk_dir)"/ndk/* | sort -V | tail -1)"
  [ -x "$ndk/ndk-build" ] || { echo "NDK not found in $(sdk_dir)/ndk" >&2; exit 1; }
  echo "$ndk"
}

checkout() { # core, repo, commit
  local src="$WORK/$1"
  [ -d "$src/.git" ] || git clone --quiet "$2" "$src"
  if ! git -C "$src" cat-file -e "$3^{commit}" 2>/dev/null; then
    git -C "$src" fetch --quiet origin "$3" || git -C "$src" fetch --quiet --unshallow origin || git -C "$src" fetch --quiet origin
  fi
  git -C "$src" reset --quiet --hard # drops files an earlier patch staged
  git -C "$src" checkout --quiet --force --detach "$3"
  git -C "$src" clean -qfdx >/dev/null
  # The 3D cores keep their dependencies as submodules, pinned by the commit.
  if [ -f "$src/.gitmodules" ]; then
    git -C "$src" submodule --quiet sync --recursive
    git -C "$src" submodule --quiet update --init --recursive --force --depth 1 ||
      git -C "$src" submodule --quiet update --init --recursive --force
    git -C "$src" submodule --quiet foreach --recursive 'git reset --quiet --hard && git clean -qfdx'
  fi
  for patch in "$CORES/patches/$1"/*.patch; do
    # --index: files a patch adds must survive the clean before each iOS slice.
    [ -f "$patch" ] && git -C "$src" apply --index "$patch"
  done
  return 0
}

fingerprint() { # core, commit, build, extra, android extra
  { echo "$2|$3|$4${5:+|$5}${CORE_NDK:+|ndk$CORE_NDK}"; cat "$CORES/patches/$1"/*.patch 2>/dev/null || true; } | shasum | cut -c1-16
}

build_android() { # core, src, key, build, jni, targets, extra
  local abi
  for abi in $ANDROID_ABIS; do
    # A core too heavy for a TV box is built for the 64-bit phones alone.
    [ -z "$6" ] || [[ " $6 " == *" $abi "* ]] || continue
    build_android_abi "$1" "$2" "$3" "$4" "$5" "$abi" "$7"
  done
}

build_android_abi() { # core, src, key, build, jni, abi, extra
  local abi="$6" key="$3-api$ANDROID_API" extra="$7"
  # A core with dependencies of its own builds them first and adds what points CMake at them.
  local hook="$CORES/hooks/$1-android.sh"
  [ -f "$hook" ] && extra="$extra $(PATH="$(cmake_bin):$PATH" NDK="$(ndk_dir)" ABI="$abi" API="$ANDROID_API" SRC="$2" \
    DEPS="$WORK/deps/$1-$abi" JOBS="$JOBS" bash "$hook")"
  local target="$OUT/android/$abi/lib$1_libretro_android.so" stamp="$OUT/android/$abi/.$1.build"
  [ -f "$target" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$key" ] && return
  mkdir -p "$(dirname "$target")"
  if [[ "$4" == cmake:* ]]; then
    local build="$WORK/build/$1-android-$abi"
    "$(cmake_bin)/cmake" -S "$2" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$(cmake_bin)/ninja" \
      -DCMAKE_TOOLCHAIN_FILE="$(ndk_dir)/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" \
      -DANDROID_PLATFORM="android-$ANDROID_API" -DCMAKE_BUILD_TYPE=Release ${4#cmake:} $extra
    "$(cmake_bin)/ninja" -C "$build" -j "$JOBS"
    # A fork may keep its upstream library name.
    cp "$(find "$build" -name "*_libretro*.so" | head -1)" "$target"
    "$(ls "$(ndk_dir)"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)" --strip-unneeded "$target"
  else
    rm -rf "$WORK/libs/$1-$abi" "$WORK/obj/$1-$abi"
    # STRINGS: mupen64plus reads its assembly offsets with the NDK's tool, which it looks for under linux-x86_64.
    "$(ndk_dir)/ndk-build" -C "$2/$5" -j"$JOBS" APP_ABI="$abi" APP_PLATFORM="android-$ANDROID_API" \
      NDK_OUT="$WORK/obj/$1-$abi" NDK_LIBS_OUT="$WORK/libs/$1-$abi" \
      STRINGS="$(ls "$(ndk_dir)"/toolchains/llvm/prebuilt/*/bin/llvm-strings | head -1)" $extra
    cp "$(find "$WORK/libs/$1-$abi/$abi" -name "*.so" | head -1)" "$target"
  fi
  echo "$key" > "$stamp"
}

# The engine draws through ANGLE on iOS, so a core that draws with OpenGL ES has to call ANGLE too, not Apple's
# own OpenGL ES, which has no context here. Cores include <OpenGLES/ES3/gl.h> and link "-framework OpenGLES": this
# makes a framework of that name, with the SDK's own headers (the ones the cores were written against) and ANGLE's
# libGLESv2 as its binary, and puts it in front of the SDK's. The functions are the same ones; the core then
# loads @rpath/libGLESv2.framework, the one the app already carries.
ios_gl_shim() { # sdk(iphoneos|iphonesimulator)
  local slice=ios-arm64 angle sdk_path shim="$WORK/ios-gl/$1"
  [ "$1" = iphoneos ] || slice=ios-arm64-simulator
  angle="$(cd "${ANGLE:-$HERE/../../.cache/angle}" && pwd)"
  sdk_path="$(xcrun --sdk "$1" --show-sdk-path)"
  rm -rf "$shim" && mkdir -p "$shim/OpenGLES.framework"
  ln -s "$sdk_path/System/Library/Frameworks/OpenGLES.framework/Headers" "$shim/OpenGLES.framework/Headers"
  ln -s "$angle/libGLESv2.xcframework/$slice/libGLESv2.framework/libGLESv2" "$shim/OpenGLES.framework/OpenGLES"
  echo "$shim"
}

build_ios_slice() { # core, src, build, extra, sdk(iphoneos|iphonesimulator)
  local name="$1_libretro" sdk_path dylib min_flag platform_name extra=" $4 " gl="" defines="" word kept=""
  sdk_path="$(xcrun --sdk "$5" --show-sdk-path)"
  if [ "$5" = iphoneos ]; then
    min_flag="-miphoneos-version-min=$IOS_MIN"; platform_name=iPhoneOS
  else
    min_flag="-mios-simulator-version-min=$IOS_MIN"; platform_name=iPhoneSimulator
  fi
  # "GL=angle" among a core's iOS arguments asks for the OpenGL ES above; "@SRC@" stands for its source folder.
  if [[ "$extra" == *" GL=angle "* ]]; then
    extra="${extra/ GL=angle / }"
    gl="$(ios_gl_shim "$5")"
    # IOS is how libretro's own headers know to take OpenGL ES from the framework.
    gl="-F$gl -DIOS"
  fi
  extra="${extra//@SRC@/$2}"
  # "CFLAG=<flag>" words are compiler flags for the core's iOS build, apart from its own build arguments.
  for word in $extra; do
    if [[ "$word" == CFLAG=* ]]; then defines="$defines ${word#CFLAG=}"; else kept="$kept $word"; fi
  done
  extra="$kept"
  if [[ "$3" == cmake:* ]]; then
    local build="$WORK/build/$1-$5" arguments="${3#cmake:}" flags=()
    # A core whose iOS build is configured differently from its Android one says how.
    [ -z "${extra// /}" ] || arguments="$extra"
    [ -z "$gl$defines" ] || flags=(-DCMAKE_C_FLAGS="$gl$defines" -DCMAKE_CXX_FLAGS="$gl$defines"
      -DCMAKE_OBJC_FLAGS="$gl$defines" -DCMAKE_OBJCXX_FLAGS="$gl$defines"
      -DCMAKE_SHARED_LINKER_FLAGS="$gl" -DCMAKE_MODULE_LINKER_FLAGS="$gl")
    "$(cmake_bin)/cmake" -S "$2" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$(cmake_bin)/ninja" \
      -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
      -DCMAKE_OSX_SYSROOT="$sdk_path" -DCMAKE_BUILD_TYPE=Release ${flags[@]+"${flags[@]}"} $arguments
    "$(cmake_bin)/ninja" -C "$build" -j "$JOBS"
    dylib="$(find "$build" -name "*_libretro*.dylib" | head -1)"
  else
    local dir="$2/$(dirname "$3")" makefile compilers=()
    makefile="$(basename "$3")"
    git -C "$2" clean -qfdx >/dev/null
    [ -z "$gl$defines" ] || compilers=(CC="clang -arch arm64 -isysroot $sdk_path $min_flag $gl$defines"
      CXX="clang++ -arch arm64 -isysroot $sdk_path $min_flag $gl$defines")
    make -C "$dir" -f "$makefile" -j"$JOBS" platform=ios-arm64 IOSSDK="$sdk_path" MINVERSION="$min_flag" \
      ${compilers[@]+"${compilers[@]}"} $extra
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

build_ios() { # core, src, key, build, extra, targets
  local name="$1_libretro" stamp="$OUT/ios/.$1.build" simulator="$SIMULATOR"
  # "ios-device" among a core's targets: built for the phone only, where its simulator build is not kept up.
  [[ " $6 " != *" ios-device "* ]] || simulator=0
  local key="$3-sim$simulator"
  [ -d "$OUT/ios/$name.xcframework" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$key" ] && return
  local frameworks=()
  build_ios_slice "$1" "$2" "$4" "$5" iphoneos
  frameworks+=(-framework "$WORK/ios/iphoneos/$name.framework")
  if [ "$simulator" = 1 ]; then
    build_ios_slice "$1" "$2" "$4" "$5" iphonesimulator
    frameworks+=(-framework "$WORK/ios/iphonesimulator/$name.framework")
  fi
  mkdir -p "$OUT/ios"
  rm -rf "$OUT/ios/$name.xcframework"
  xcodebuild -create-xcframework "${frameworks[@]}" -output "$OUT/ios/$name.xcframework" >/dev/null
  echo "$key" > "$stamp"
}

# The files a core reads from the frontend's system folder (PPSSPP's fonts and atlases), zipped from its pinned
# source: assets.properties names the source folder and what of it to leave out. The app unpacks the zip there.
pack_assets() { # core, src
  local line dir exclude zip="$OUT/assets/$1.zip"
  line="$(grep "^$1|" "$CORES/assets.properties" 2>/dev/null || true)"
  [ -n "$line" ] || return 0
  IFS='|' read -r _ dir exclude <<< "$line"
  mkdir -p "$OUT/assets"
  rm -f "$zip"
  # -X and a fixed order: no owner or extra fields, and the same listing whoever builds it.
  (cd "$2/$dir" && find . -type f | LC_ALL=C sort | grep -vE "^\./($exclude)" | TZ=UTC zip -q -X -9 -@ "$zip")
}

grep -v '^\s*#' "$CORES/cores.properties" | grep -v '^\s*$' | while IFS='|' read -r core repo commit build jni ios_extra targets android_extra ndk; do
  [ "$ONLY" = "  " ] || [[ "$ONLY" == *" $core "* ]] || continue
  # targets: the ABIs and "ios" (or "ios-device") this core is built for; empty means all of them.
  [ "$PLATFORM" != ios ] || [ -z "$targets" ] || [[ " $targets " == *" ios"* ]] || continue
  CORE_NDK="$ndk"
  echo "core $core @ ${commit:0:7} ($PLATFORM)"
  checkout "$core" "$repo" "$commit"
  key="$(fingerprint "$core" "$commit" "$build" "$ios_extra" "$android_extra")"
  case "$PLATFORM" in
    android)
      build_android "$core" "$WORK/$core" "$key" "$build" "$jni" "$targets" "$android_extra"
      pack_assets "$core" "$WORK/$core" ;;
    ios) build_ios "$core" "$WORK/$core" "$key" "$build" "$ios_extra" "$targets" ;;
    *) echo "plataforma desconhecida: $PLATFORM" >&2; exit 1 ;;
  esac
done
