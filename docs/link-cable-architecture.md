# Link Cable integration

The link-cable path is separate from deterministic controller netplay. Each
peer owns a complete emulator instance and the transport carries only serial
link events.

## Runtime path

```text
GB/GBC serial or GBA SIO driver
        -> native link adapter
        -> KMP LinkCableSession
        -> framed TCP/Bonjour or NSD transport
```

The mGBA GBA implementation uses `GBASIOLockstepDriver` as the timing
authority. The network adapter must provide ordered transfer events and wake
the core when the matching peer event arrives. It must never send video,
audio, controller input, or a full save state on the hot path.

## Compatibility contract

The handshake includes protocol version, system family, ROM fingerprint, core
version, player count, and a session nonce. A session is rejected if any
determinism-affecting field differs. Transfer packets contain a sequence,
emulated cycle, and serial value; reset and disconnect are explicit events.

## Platform boundary

`commonMain` owns the wire codec, session state, validation, timeout policy,
and metrics. `androidMain` and `iosMain` own only socket/discovery details.
The native bridge owns installation and removal of the GB/GBC serial or GBA
SIO driver in the core. This keeps the emulator loop free of UI and coroutine
allocations.

## State and saves

Save states are disabled while a live cable session is active unless all peers
checkpoint together. Battery saves remain per logical cartridge. On a failed
session, the core resets the link driver before returning to solo mode.
