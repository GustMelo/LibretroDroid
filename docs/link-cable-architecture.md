# Link Cable

Two devices on the same LAN run their own copy of the game and connect their
emulated link ports. No video or audio crosses the network. Android and iOS
use the same cores from the same pinned commits, so they pair with each other.

| System | Core | Link data path |
| --- | --- | --- |
| GB / GBC | `gambatte` (libretro/gambatte-libretro, patched) | Gambatte's serial socket, TCP port 56400 |
| GBA | `mgba_link` (Aelvryx/mgba-wifi-link) | Libretro Netpacket over `NetpacketSession` |
| Game Gear | `genesis_plus_gx_link` (libretro/Genesis-Plus-GX, patched) | Libretro Netpacket over `NetpacketSession` |

These are separate cores from the regular `mgba` and `genesis_plus_gx`: an app
loads them only for a link session, so ordinary save states and play are
unaffected.

## Pairing (KMP, shared)

`LinkLobby` advertises on the existing `_libretrodroid._tcp` service with the
game key `link|<rom>|<core>|<core version>`. Of two devices with the same key,
the one that started later connects to the earlier one, so exactly one TCP
connection exists. The hello checks the key again; a mismatch is rejected
before any core sees data. The earlier device is `localId` 0 (host).

- GB/GBC: the host sets `gambatte_gb_link_mode=Network Server`, the joiner
  `Network Client` with the host's IPv4 address in the twelve
  `gambatte_gb_link_network_server_ip_N` digits. The pairing connection stays
  open as `LinkControl`, a one-second heartbeat that reports a vanished peer.
- GBA and Game Gear: the pairing connection becomes the `NetpacketSession`
  transport; the host is Netpacket client 0 and reports client 1 as connected.

## Threading and lifetime

The native bridge only invokes core callbacks on the emulation thread. Socket
threads enqueue packets into bounded queues (64 packets, at most 4 MiB per
direction); `pump()` delivers them before each frame. `closeNetwork()` never
calls the core. Pausing the player, backgrounding it or destroying it stops the
session before the core is unloaded.

The Gambatte patch keeps a silent peer from freezing emulation: TCP_NODELAY,
two-second send/receive timeouts, a one-second connect timeout, exact two-byte
reads, SO_REUSEADDR and a monotonic reconnect throttle.
A second patch fixes upstream Gambatte's received-bit shifting when a game
reads SB/SC in the middle of a network transfer (polling instead of the serial
interrupt): it took the byte's top bits again and corrupted it.
`scripts/test-gb-link.sh` runs two patched cores on the Mac, linked over
127.0.0.1, with a generated serial test ROM; each must receive its partner's
bytes intact. The release script runs it.

While a link is active the players refuse save states, state loads, reset,
fast-forward and controller netplay: each would desynchronize the two sides.
Battery saves stay per player.

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
partner that joins late and one that leaves. The release script runs it.
On a device the same ROM shows red, then green once the serial exchange is
complete.

## GBA reference

Aelvryx/mgba-wifi-link at 9e919b0cfbb93af7d1171570dfc6745d00eeebab replicates
both machines on each device and synchronizes inputs, so serial words need no
network round trip. Its documented scope is two-player Multi-Pak: Mario Kart:
Super Circuit, Zelda: Four Swords and Advance Wars were qualified upstream.
Single-Pak multiboot, the Wireless Adapter and four-player sessions are not
supported.

## Limits

- Two players per link session.
- Same LAN only; no internet relay.
- Game Gear link adds 50 ms of cable latency; games that expect an answer
  within a few scanlines of a byte would time out.
- GB/GBC link is a byte-for-byte serial exchange with a network round trip per
  master transfer: trades and battles work; timing-critical real-time link
  games may stutter on slow Wi-Fi.

The earlier `LinkCableProtocol` byte codec is not wired to any core.
