# Upstream provenance and maintenance

Original: https://github.com/Swordfish90/LibretroDroid
Base: 0.14.0, `8835c3098514390a271e36983957f7bb5f40abf1`.

The `multiplatform` branch is based directly on that upstream commit. The initial
unrelated extraction is not used as its parent. `git merge-base` and normal upstream
merges therefore use the original code as their common ancestor.

```sh
git fetch upstream master
git switch -c updates/upstream-<version> multiplatform
git merge --no-ff upstream/master
scripts/test-native.sh
./gradlew :netplay:allTests :libretrodroid:assembleRelease
```

The native source was reorganized from `libretrodroid/src/main/cpp` into `native/src`
and `native/bindings`. Git can detect many renames, but upstream changes to moved or
split files may require manual resolution. Keep general enhancements in focused
commits and propose them upstream when appropriate. Acceptance upstream reduces
future maintenance; a clean merge alone does not prove compatibility.

Android/iOS runtime release and optional core packs are versioned together. The
app pins both version and SHA-256, so native or core updates cannot silently reach
users. Never edit an already released archive: publish a new version.

Third-party source currently vendored by the original extraction:
- google/oboe: `b15f5e39c01a7ada306d959e5129620b145fb8b4` (include, src, CMakeLists).
- libretro/libretro-common: `b0c348ea5543c4d7fb0bc479258aa6988b20c0c9` (without samples).
- Emulator cores: exact repos/commits in `native/cores/cores.properties`; local
  patches in `native/cores/patches`.
- ANGLE: commit pinned in `native/scripts/build-angle-ios.sh`.

Licenses and copyright notices remain with their corresponding source.
The runtime is GPL-3.0; core and ANGLE terms are independent.
