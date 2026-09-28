# Link Cable integration status

This is work in progress. The app does not yet provide a working link session.
The current release dependency remains unchanged.

## Chosen transport boundary

Use the current Libretro Netpacket ABI (environment command 78), not the old
experimental command 76. Core start receives send and poll-receive callbacks;
broadcast is destination 0xffff. Reliable ordering is provided by TCP.
The native bridge must only invoke core callbacks on the emulation thread.
Socket receiver threads must enqueue packets for that thread, never call the
core directly. Stop must run before unloading the core shared library.

KMP's NetpacketChannel frames opaque core messages with a bounded 64 KiB
payload and reuses its receive buffer. It does not interpret emulated serial
registers. Its buffer must be copied into bounded session storage before the
next read if the consumer has not finished.

## GBA reference

Aelvryx/mgba-wifi-link at 9e919b0cfbb93af7d1171570dfc6745d00eeebab
is the evaluated reference, not yet the packaged app core. It uses replicated
P0/P1 machines: each peer simulates both cartridges and synchronizes inputs.
Local SIO scheduling therefore avoids a network round trip per serial word.
This costs additional CPU and memory; measurements on devices remain required.
Its current documented scope is two-player GBA Multi-Pak. This does not prove
GB/GBC, four-player GBA, or arbitrary game compatibility.

A host build and real-core lifecycle smoke test pass. The smoke test loads
the supplied diagnostic ROM, registers Netpacket, starts/stops a host and
unloads the core. It does NOT prove data exchange between two linked cores.

## Remaining integration

- Bounded session queues, handshake, timeout and close behavior.
- JNI and Kotlin/Native bindings with emulation-thread dispatch.
- GBA linked-pair integration and GB/GBC linked execution.
- Independent local battery-save ownership; forbid unsafe live state loads.
- App host/join UI, discovery, network permission and lifecycle integration.
- Two-core diagnostic exchange, platform builds and physical-device validation.
- Versioned artifacts, publication and app dependency update.

The earlier LinkCableProtocol byte-transfer codec is not wired to any core
and is not the wire format of the GBA reference. It must not be presented as
working link support or used to truncate 16/32-bit SIO data.
