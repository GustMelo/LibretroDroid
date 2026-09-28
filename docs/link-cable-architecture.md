# Link Cable

Two devices on the same LAN run their own copy of the game and connect their
emulated link ports. No video or audio crosses the network. Android and iOS
use the same cores from the same pinned commits, so they pair with each other.

| System | Core | Link data path |
| --- | --- | --- |
| GB / GBC | `gambatte` (libretro/gambatte-libretro, patched) | Gambatte's serial socket, TCP port 56400 |
| GBA | `mgba_link` (Aelvryx/mgba-wifi-link) | Libretro Netpacket over `NetpacketSession` |

These are separate cores from the regular `mgba`: an app loads them only for a
link session, so ordinary save states and play are unaffected.

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
- GBA: the pairing connection becomes the `NetpacketSession` transport; the
  host is Netpacket client 0 and reports client 1 as connected.

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
- GB/GBC link is a byte-for-byte serial exchange with a network round trip per
  master transfer: trades and battles work; timing-critical real-time link
  games may stutter on slow Wi-Fi.

The earlier `LinkCableProtocol` byte codec is not wired to any core.
