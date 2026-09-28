#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
scripts/test-native.sh
scripts/test-gb-link.sh
scripts/test-gg-link.sh
native/scripts/build-angle-ios.sh
native/scripts/build-cores.sh android
native/scripts/build-cores.sh ios
./gradlew :netplay:allTests :libretrodroid:publishAllPublicationsToDistributionRepository :netplay:publishAllPublicationsToDistributionRepository :player:publishAllPublicationsToDistributionRepository
# Use a fresh output directory so an earlier XCFramework cannot contaminate this release.
output="$(mktemp -d "$ROOT/build/native-package.XXXXXX")"
xcodebuild -create-xcframework \
  -library "$ROOT/.cache/engine/ios-arm64/libretroengine.a" -headers "$ROOT/.cache/engine/include" \
  -library "$ROOT/.cache/engine/ios-simulator-arm64/libretroengine.a" -headers "$ROOT/.cache/engine/include" \
  -output "$output/LibretroEngine.xcframework"
python3 - "$output" <<'PY'
from pathlib import Path
import shutil, sys
source = Path(sys.argv[1])
target = Path('build/native-xcframework')
if target.exists():
    shutil.rmtree(target)
source.rename(target)
PY
python3 scripts/package-release.py
