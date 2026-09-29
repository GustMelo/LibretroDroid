# Link Cable

Players connect their emulated link ports: on one device, between devices on a
LAN, Android and iOS alike. No video or audio crosses the network. Android and
iOS use the same cores from the same pinned commits, so they pair with each other.

| System | Core | How the cable works |
| --- | --- | --- |
| GB / GBC | `mgba` (retrolink patch) | 2 consoles linked inside the core; netplay carries only input |
| GBA | `mgba` (retrolink patch) | up to 4 consoles (Multi-Pak) linked inside the core; netplay carries only input |
| Game Gear | `genesis_plus_gx_link` (patched) | one console per device, Libretro Netpacket over `NetpacketSession` |

## GB, GBC and GBA: retrolink

The `mgba` patch (`native/cores/patches/mgba`, file `retrolink.inl`) lets the
regular mGBA core run several consoles at once, linked by a cable inside the
core. Console 0 is the game the frontend loaded; consoles 1-3 are clones of the
same ROM (and GBA BIOS) with their own save RAM. Controller port p drives
console p. Every console runs on the emulation thread, in a fixed order:

- GBA: mGBA's own lockstep coordinator (`GBASIOLockstepCoordinator`), the one
  its desktop frontend uses for Multi-Pak, with cooperative sleep/wake instead
  of threads (as Aelvryx/mgba-wifi-link's replicated pair does). Up to 4.
- GB/GBC: a pair driver. When a console starts a transfer on its own clock it
  receives its partner's SB; the partner, waiting on the external clock,
  receives the starter's SB and completes after the same bit time.

The same inputs therefore give the same result on every device, and the
regular netplay (`NetplaySession`: LAN discovery, input sync, state hashes)
turns this into a link session. Only controller input crosses the network: a
serial transfer never waits for the network, whatever the latency, so the
feel is that of netplay (input delay), not of a cable round trip.

Frontend API (optional symbols; cores without them report 1 player):

| Core export | Meaning |
| --- | --- |
| `retro_link_max_players()` | 4 for GBA, 2 for GB/GBC |
| `retro_link_set_players(n)` | n linked consoles; those that stay keep running, new ones boot with their save |
| `retro_link_set_local(p)` | this device plays console p: its picture, its sound and `RETRO_MEMORY_SAVE_RAM` |
| `retro_link_set_grid(on)` | every console on one screen (side by side, 2x2 for 3-4), for players sharing a device |
| `retro_link_load_save(p, data, size)` | console p gets its player's save and boots again with it |
| `retro_link_keep(p)` | console p goes on alone as console 0 (state and save) |

A state (`retro_serialize`) holds every console with its save. Loading one
rebuilds the cable, which is not part of a console's state; the netplay host
loads its own state before sending it, so every device starts the cable from
the same point and stays byte-identical.

Netplay (protocol 8): the joiner's `Hello` carries its save; the host gives it
a console with that save (`NetplayEmulator.linkConsoles`). When a player leaves
and ports move, every console but the host's boots again with its player's
current save, so nothing saved in the session is lost. Clients keep their own
save RAM (no shared save swap), and when the session ends a client keeps only
the console it played (`linkKeepLocal`). Android's `GLRetroView` and iOS's
`RetroPlayer` implement these hooks; `setLocalLinkPlayers(n)` is the
same-device mode, where each physical controller plays its own port.

`scripts/test-retrolink.sh` builds the patched core for macOS and runs 2 Game
Boys with a generated serial ROM (`make_gb_link_rom.py`) and 2, 3 and 4 GBAs
with a generated Multi-Pak ROM (`gba_link4.c`, built by clang alone): every
console must exchange its words correctly, and a second core instance that
joined from the first's state must stay byte-identical. The release script
runs it.

## Pairing for the Game Gear (KMP, shared)

`LinkLobby` advertises on the existing `_libretrodroid._tcp` service with the
game key `link|<rom>|<core>|<core version>`. Of two devices with the same key,
the one that started later connects to the earlier one, so exactly one TCP
connection exists. The hello checks the key again; a mismatch is rejected
before any core sees data. The earlier device is `localId` 0 (host). The
pairing connection becomes the `NetpacketSession` transport.

## Threading and lifetime

The native bridge only invokes core callbacks on the emulation thread. Socket
threads enqueue packets into bounded queues (64 packets, at most 4 MiB per
direction); `pump()` delivers them before each frame. `closeNetwork()` never
calls the core. Pausing the player, backgrounding it or destroying it stops the
session before the core is unloaded.

While a Netpacket link is active the players refuse save states, state loads,
reset, fast-forward and controller netplay: each would desynchronize the two
sides. Battery saves stay per player.

## Game Gear (Gear-to-Gear)

Upstream Genesis Plus GX keeps the EXT port registers ($01-$05) unconnected.
The `genesis_plus_gx_link` patch (`core/gg_link.c`) wires them to a cable made
of Netpacket packets, following the pinout on smspower.org
(Development/GearToGearCable) and BizHawk's GGHawkLink:

- Parallel: PC0-PC6, direction in port $02 (1 = input), crossed 0<->2, 1<->3,
  4<->5, 6<->6. Undriven inputs read 1 (pull-up). With port $02 bit 7 set, a
  falling PC6 input raises the NMI.
- Serial: port $05 bits 4/5 turn the UART on (PC4 out, PC5 in), bits 6-7 pick
  4800/2400/1200/300 bps. $03 sends a byte and holds bit 0 (send buffer full)
  for its ten bit times; an arriving byte lands in $04, sets bit 1 and, with
  bit 3, raises the NMI; reading $04 clears bit 1. When the peer leaves, bit 2
  (partner off) is set and, with serial on, the NMI is raised.

Each console runs on its own device. After every frame a side sends one reliable
packet: the frame number and that frame's events (pins it drives, bytes it
sends), each stamped with its Z80 cycle. The partner replays frame `f - 3`
during its frame `f`, each event once its own Z80 reaches that cycle (checked
at every scanline and at every EXT port read). A frame whose packet has not
arrived waits up to 8 ms, then is repeated (duplicate video, no audio); ten
seconds of silence count as the partner leaving. The cable therefore has a
fixed 3-frame (50 ms) latency, the two consoles never drift apart, and every
operation is O(1) per event. Protocol-driven games (handshakes, polling,
NMI) are unaffected by the latency; a serial round trip takes six frames.

`scripts/test-gg-link.sh` runs two patched cores on the Mac joined by an
in-memory cable with a generated test ROM (`make_gg_link_rom.py`): 64 serial
bytes each way under the receive NMI, 16 parallel echo rounds, the PC6 NMI, a
partner that joins late and one that leaves; then both again with the cable
plugged in while they already play, as on devices, where pairing takes a
moment. The release script runs it. The ROM waits for its partner on the
parallel pins before sending (a byte sent unplugged is lost, as on hardware):
on a device it shows red until the other one is linked, then blue on both.

## Limits

- GB/GBC link is two players (the DMG-07 four-player adapter is not emulated);
  GBA Multi-Pak up to four.
- Single-Pak multiboot and the GBA Wireless Adapter are not emulated yet.
- Game Gear link adds 50 ms of cable latency; games that expect an answer
  within a few scanlines of a byte would time out.

The earlier `LinkCableProtocol` byte codec is not wired to any core.
