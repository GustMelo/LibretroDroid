# LibretroDroid Multiplatform

A LibretroDroid fork with an Android frontend, an iOS player using ANGLE/Metal,
and a shared Kotlin Multiplatform netplay implementation. No application UI,
ROM library, NFC or app-specific storage policy is included.

Based on Swordfish90/LibretroDroid 0.14.0, commit
`8835c3098514390a271e36983957f7bb5f40abf1`. Original GPL-3.0 license and
copyright notices are preserved. This is an independent fork, not an official release.

## Modules

- `libretrodroid`: Android AAR, GLRetroView and JNI frontend.
- `native`: shared C++ runtime, platform audio/input, JNI and C bindings.
- `player`: iOS Kotlin library with the native runtime embedded in its cinterop KLIB.
- `netplay`: KMP protocol, session, LAN discovery and sockets (Android/iOS).
- `native/cores`: independently pinned cores and their patches, optional for consumers.

Supported builds: Android arm64-v8a; iOS arm64 device and arm64 simulator.

## Build and publish

Use JDK 21, Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1.
iOS builds require macOS and Xcode. First build ANGLE:

```sh
native/scripts/build-angle-ios.sh
./gradlew :netplay:allTests :libretrodroid:publishAllPublicationsToDistributionRepository :netplay:publishAllPublicationsToDistributionRepository :player:publishAllPublicationsToDistributionRepository
```

Artifacts are written to `build/maven` with Gradle metadata and transitive dependencies.
An Android AAR alone is insufficient: include netplay and its dependencies as well.
KMP apps consume `player` through Maven metadata; Swift-only apps can use the C API
and the native XCFramework. ANGLE must also be linked on iOS.

The Release archive contains a Maven repository (AAR + KLIBs), optional core binaries,
and iOS frameworks. Consumers pin the version and archive SHA-256. They do not need
a source checkout of this repository or native build tools for the runtime.

## Upstream updates

The maintenance branch is `multiplatform`, based directly on the upstream tag.
See [UPSTREAM.md](UPSTREAM.md). Keep changes as focused commits and test on both
platforms before releasing. A conflict-free merge does not guarantee runtime compatibility.

To build all release archives on macOS, run `scripts/build-distribution.sh`.
Upload `build/distribution/*.zip` and `manifest.json` to a versioned GitHub Release;
the manifest records the source commit and every archive SHA-256. Tests and
consumer integration must pass before making the Release public.
