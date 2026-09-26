# LAN netplay validation

The session supports up to four devices. Protocol v6 normalizes PCSX diagnostic fields in state hashes and retains the 5-second performance report introduced in v5; older peers are rejected before starting emulation. Inputs remain immediate UDP datagrams, with eight-frame redundancy and an 18-frame resend window. TCP carries session control and synchronized state.

## Changes

- Preallocated input ring and encoding buffer; Android reuses its outgoing datagram and resolved endpoints.
- Requested UDP send/receive buffers: 256 KiB on Android and iOS. Actual sizes are OS-dependent.
- Bounded startup pong queue, matched nonce/address/timestamp, serialized startup probes. Periodic pongs no longer accumulate indefinitely.
- Initial delay uses 12 probes and p90 RTT plus median absolute deviation. Live measurements retain at most 30 samples per peer.
- Native reports expose stalls, maximum replay depth, rollback count and total replayed frames for each client. Local detailed timings remain in `netplay stats`.
- Three consecutive network evaluations can increase delay one frame. Playback pressure requires two consecutive native reports with at least three stalls or a replay depth of four. Reports expire after 12 seconds. Increases have a 60-second cooldown; delay is not reduced during a running session.
- An increase uses the existing state synchronization path: it may cause a brief interruption. It is not a seamless change to the frame timeline.
- Input source/port validation and an admission check under the roster lock preserve the four-player limit.

`frameGaps` measures discontinuities in the newest received frame; it is not a packet-loss percentage. Redundancy, resends and reordering make an exact loss estimate unavailable without a separate packet sequence.

## Automated checks

Run `./gradlew :engine:netplay:testAndroidHostTest :engine:netplay:compileKotlinIosArm64`.

Tests cover four real loopback sessions relaying inputs, rejection of a fifth player and protocol v4, encoder compatibility across ring wrap/gaps, adaptation hysteresis and stale telemetry. A seeded simulation sends 1,800 frames from each of four input streams with 1%, 3% and 5% independent loss and 0–4 ticks of delay/reordering, then verifies recovery after a final resend. This checks the input transport model, not real emulator determinism or frame pacing.

## Device check

Use matching Release builds and the same Bloody Roar 2 dump on Android/iPhone. Join the LAN session, select two players and play with inputs from both devices. Capture `Netplay` and `netplay stats` logs; inspect FPS, stalls, replay maximum, snapshot/run cost, desync and delay-change events. Sustained performance on real Wi-Fi remains a separate validation from the automated tests.

## Bloody Roar 2 device finding

The first device run showed repeated hash mismatches at frame 120, RTT p90 around 8–21 ms and zero observed input frame gaps in the captured interval. Frequent resyncs prevented a stable five-second performance window.

PCSX at pinned commit ff81ed17 serializes `origin_info.build_info` (including the compiler version) and `misc_save_data.save_counter`. These diagnostic fields are not deterministic emulation state. The hash now skips exactly 64 build-info bytes and 4 save-counter bytes when the PCSX header, save version 0x8b410006 and MISC magic match. Snapshots themselves remain untouched; emulation options, CPU/memory/device state remain checked. Unknown formats retain the full hash. Field offsets come from `libpcsxcore/misc.c` and `boolean = uint8_t` in `psxcommon.h` of that pinned core.

Run `c++ -std=c++17 engine/native/tests/netplay_state_hash_test.cpp -o /tmp/gip-state-hash-test && /tmp/gip-state-hash-test` to check metadata invariance and sensitivity to RAM, settings, timing and unknown formats. Repeat the physical-device game test after installing both updated builds; other state differences may still exist.

## Follow-up: playback continuity and asynchronous hash

The user confirmed matching characters, actions, life/time and independent controls on both devices. After disabling repeated automatic resync, the Android trace showed approximately 60 FPS and zero stalls after startup, but whole-state hashes still differed. Visual agreement is not proof of full-state determinism. The remaining mismatch is unresolved.

Rollback hash checks are now scheduled every 600 frames on both platforms. A persistent worker computes them from an owned snapshot copy, with one bounded pending slot. Results carry an internal generation and are discarded after stop/restart. The emulation thread still copies the snapshot; it no longer scans all its bytes to compute the hash. First-check 64 KiB block fingerprints are logged on both platforms via `state-block`, for comparison without exposing raw game state. Automatic resync on hash mismatch remains disabled for this diagnostic run; this is not a claim that divergence is repaired.

Native tests additionally cover asynchronous snapshot ownership and discarding results from stopped/previous sessions (`state_hash_worker_test.cpp`, `netplay_generation_test.cpp`). Real-device frame-time improvement from the worker still needs measurement.

## iOS incremental build defect found during block diagnosis

Android emitted 68 block fingerprints but iOS emitted none. The Xcode/Gradle log showed `buildNativeEngine` executed while `cinteropRetroengineIosArm64` and `linkReleaseFrameworkIosArm64` were UP-TO-DATE. The cinterop definition embeds `libretroengine.a` via `staticLibraries`, but the archive was not declared as an input. Thus a newly built app could contain an older native engine even though Kotlin/protocol versions matched.

Each target's cinterop task now declares its native archive as a content-sensitive input; the native build script is also a build task input. The subsequent build executed both cinterop and framework linking. This invalidates earlier claims that the physical devices necessarily ran the same native changes. Cross-device hashes must be retested after reinstalling the corrected iPhone build.

After reinstalling the corrected native iOS build, both devices emitted all 68 fingerprints. Of these, 64 matched; four differed (byte offsets 65,536; 2,097,152; 2,686,976; 4,325,376, each covering 64 KiB). This narrows the outstanding state comparison and confirms that the previously absent iOS diagnostics were a build problem. It does not establish that the four differences are harmless; no additional state bytes have been excluded from validation.

## Frame-600 raw state comparison: interpreter sub-cycle loss

Raw snapshots captured on both devices at frame 600 (Bloody Roar 2, both on the interpreter, identical `origin_info` options) differed in only 93 of 4,456,448 bytes. Aside from the already excluded build string, all differences were CPU timing: `psxRegs.cycle` and the `intCycle`/`event_cycles` schedules were 5 cycles apart (Android 439,680,564 vs iOS 439,680,559), along with the derived `gteBusyCycle`, `muldivBusyCycle` and `gpuIdleAfter`. PC and frame counter matched. A few RAM words (stack/timers) and SPU/CD fields differed as knock-on effects.

Cause: at the default `cycle_multiplier` of 175, the interpreter adds 1.75 cycles per instruction and keeps the fraction in `psxRegs.subCycle`. That field is not serialized, and `R3000ACPU_NOTIFY_AFTER_LOAD_STATE` resets it to 0. Each rollback load, and the initial state sent to the client, therefore drops up to 0.75 cycle. The peers roll back at different moments, so they drift.

Fix: core patch `0002-netplay-subcycle-state.patch` stores `subCycle` in `misc_save_data.reserved[0]` and restores it after the load notification. The value is hashed, so a remaining mismatch there will still show up. This needs to be retested on both devices.

Retest after installing the patched core on both devices: the frame-600 snapshots differed only in the excluded build string (bytes 52–110), so the hashed state was identical and no mismatch was logged. However, the Android log shows `rollbacks=0` for the whole session (RTT p90 ~9 ms), so this confirms the initial state transfer path; the rollback reload path has not yet been exercised under the patch.

## Forced rollbacks and the desktop determinism harness

With `debug.libretrodroid.delay=0` the Android host produced 2 to 6 rollbacks per 5 s window, and hashes mismatched at every 600-frame check. Cycle counters now matched; the differences were in RAM, the CD-ROM state and MDEC.

To iterate without the phones, the pinned core was built for macOS and driven by a small libretro harness. It runs Bloody Roar II with scripted inputs for both ports and takes a snapshot before every frame, as the engine does. It compares a straight run against runs with random 1 to 4 frame rollbacks and mispredicted remote input. With the unpatched core, the two runs diverged on every post-boot frame. The rollback-path differences reproduced the RAM addresses seen on the devices (0x1bca58, 0x1bd990, 0x1ff008).

`0002-netplay-deterministic-state.patch` replaces the sub-cycle-only patch. It makes `LoadState` reproduce the live emulator for these components:
- interpreter `subCycle` (saved in `misc.reserved[0]`);
- the lazily synced `HW_GPU_STATUS` copy, which GPU DMA timing reads directly (the saved value is kept, not re-synced);
- gpulib GP1 registers: reset-zero registers used to be replayed as "command i, parameter 0" and `regs[]` kept different dedupe values; the `GPUREAD` latch was not saved;
- CD-ROM `FifoSize` (it was recomputed from the current mode) and `Prev` (it was invalidated when the reread failed);
- MDEC `block_buffer_pos` (it was reset to the buffer start) and `rl`/`rl_end` offsets (they were masked on load but not on save);
- root counter reload renormalization (it rewrote `cycleStart`/`cycle`/`counterState`).

Result: three seeds with about 980 rollbacks each over 3000 frames, and per-frame hashes identical to the straight run from frame 1000 on. Final snapshots were byte-identical. The same harness on the unpatched core differed on all 2000 compared frames.

Known limits: early boot shows run-to-run nondeterminism unrelated to rollback, and a rollback across the boot-time controller type change triggers pcsx's replug window. Netplay starts from a transferred state after boot, so neither applies there. The dynarec has not been tested with this harness. Device retest pending.

Device retest (patch 0002 on both devices, Android host, forced delay 0): about 3,600 frames, up to 37 rollbacks per 5 s window, stable 60 fps, 0 hash mismatches in 6 checks. The user confirmed play was correct. Afterwards the frame-600 raw capture was removed from `state_hash_worker.h` and the forced delay was cleared. Host resync was re-enabled: a mismatch only marks resync as pending, and the host maintenance loop resends state at most once every 30 s (`RESYNC_INTERVAL_NANOS`), off the datagram thread, so repeated resync loops are not possible.
