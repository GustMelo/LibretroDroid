# Minimal Game Gear Gear-to-Gear test (CC0). Both consoles run the same ROM; the harness writes the role
# (0 or 1) to $C0F0 before the first frame. Results in RAM:
#   $C000 serial bytes received in order (64 = all), $C001 serial bytes out of order,
#   $C002 parallel rounds echoed (role 0, 16 = all), $C003 parallel NMIs on PC6 (role 1),
#   $C004 phase: 1 serial done, 2 parallel done.
# The screen (display off, backdrop only) shows the phase on a device: red, green once serial is done,
# blue once role 0 finished the parallel rounds. Two devices both run role 0 and each echoes the other's
# identical rounds, so both turn blue.
# Both wait for the partner first (each drives PC0 low, seen on the other's PC2): on devices the games run
# before the cable is plugged.
# Serial: 4800 bps with the receive NMI, each side sends 64 bytes i ^ $A5 back to back.
# Parallel: role 0 drives PC0/PC1/PC4 (and PC6), role 1 echoes what arrives on its PC2/PC3/PC5 back on
# PC0/PC1/PC4; role 0 waits for each echo, then drops PC6, which raises role 1's parallel NMI.
import sys

prog = {}; labels = {}; fixups = []; pc = [0]
def org(address): pc[0] = address
def emit(*b):
    for x in b: prog[pc[0]] = x; pc[0] += 1
def label(n): labels[n] = pc[0]
def jr(op, target): emit(op, 0); fixups.append((pc[0] - 1, target))
def word(n): return (n & 0xFF, n >> 8)

ENCODE = [0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13]   # 3-bit values on PC0, PC1, PC4

def decode_echo():                     # a = PC2/PC3/PC5 of port $01 brought back to PC0/PC1/PC4
    emit(0xDB, 0x01)                   # in a,($01)
    emit(0x47)                         # ld b,a
    emit(0x0F, 0x0F, 0xE6, 0x03)       # rrca; rrca; and $03
    emit(0x57)                         # ld d,a
    emit(0x78, 0x0F, 0xE6, 0x10)       # ld a,b; rrca; and $10
    emit(0xB2)                         # or d

def backdrop(color):                  # sprite palette entry 0, the backdrop; GG CRAM is ----BBBBGGGGRRRR
    emit(0x3E, 0x20, 0xD3, 0xBF, 0x3E, 0xC0, 0xD3, 0xBF)   # CRAM address $20
    emit(0x3E, color & 0xFF, 0xD3, 0xBE, 0x3E, color >> 8, 0xD3, 0xBE)

org(0x0000)
emit(0xF3, 0xC3, *word(0x0100))        # di; jp start

org(0x0066)                            # NMI
emit(0xF5, 0xE5)                       # push af; push hl
emit(0xDB, 0x05, 0xE6, 0x02)           # in a,($05); and $02
jr(0x28, 'parallel_nmi')               # jr z (no byte: PC6 edge)
emit(0xDB, 0x04, 0xEE, 0xA5)           # in a,($04); xor $A5
emit(0x21, *word(0xC000), 0xBE)        # ld hl,$C000; cp (hl)
jr(0x20, 'bad')
emit(0x34); jr(0x18, 'nmi_out')        # inc (hl)
label('bad')
emit(0x23, 0x34); jr(0x18, 'nmi_out')  # inc hl; inc (hl)
label('parallel_nmi')
emit(0x21, *word(0xC003), 0x34)        # ld hl,$C003; inc (hl)
label('nmi_out')
emit(0xE1, 0xF1, 0xED, 0x45)           # pop hl; pop af; retn

org(0x0100)
emit(0x31, *word(0xDFF0))              # ld sp,$DFF0
emit(0x21, *word(0xC000), 0x06, 16, 0xAF)   # ld hl,$C000; ld b,16; xor a
label('clear'); emit(0x77, 0x23); jr(0x10, 'clear')   # ld (hl),a; inc hl; djnz
backdrop(0x000F)
emit(0x3E, 0x38, 0xD3, 0x05)           # serial on, 4800 bps, receive NMI
emit(0x3E, 0x7E, 0xD3, 0x02)           # PC0 out, the rest in, no NMI
emit(0xAF, 0xD3, 0x01)                 # PC0 low: this side is plugged in
label('plug')                          # bytes sent before the partner is there are lost, as on hardware
emit(0xDB, 0x01, 0xE6, 0x04); jr(0x20, 'plug')   # until the partner's PC0 pulls PC2 low
emit(0x06, 64, 0x0E, 0x00)             # ld b,64; ld c,0
label('send')
emit(0xDB, 0x05, 0xE6, 0x01); jr(0x20, 'send')   # wait until the send buffer is free
emit(0x79, 0xEE, 0xA5, 0xD3, 0x03)     # ld a,c; xor $A5; out ($03),a
emit(0x0C); jr(0x10, 'send')           # inc c; djnz send
label('receive')
emit(0x3A, *word(0xC000), 0xFE, 64); jr(0x38, 'receive')   # until 64 bytes arrived
emit(0xAF, 0xD3, 0x05)                 # serial off
backdrop(0x00F0)
emit(0x3E, 0x01, 0x32, *word(0xC004))
emit(0x3A, *word(0xC0F0), 0xB7)        # role
jr(0x20, 'role1')

emit(0x3E, 0x2C, 0xD3, 0x02)           # role 0: PC2/PC3/PC5 in, no NMI
emit(0x0E, 0x00)                       # ld c,0 (round)
label('round')
emit(0x79, 0xE6, 0x07, 0x5F, 0x16, 0x00)          # ld a,c; and 7; ld e,a; ld d,0
emit(0x21, *word(0x0400), 0x19, 0x7E, 0x5F)       # ld hl,table; add hl,de; ld a,(hl); ld e,a
emit(0xF6, 0x40, 0xD3, 0x01)           # out value with PC6 high
label('echo')
decode_echo()
emit(0xBB); jr(0x20, 'echo')           # cp e
emit(0x7B, 0xD3, 0x01)                 # PC6 low: the partner's NMI
emit(0x21, *word(0xC002), 0x34)        # rounds++
emit(0x0C, 0x79, 0xFE, 16); jr(0x38, 'round')
emit(0x3E, 0x02, 0x32, *word(0xC004))
backdrop(0x0F00)
label('idle'); jr(0x18, 'idle')

label('role1')
emit(0x3E, 0xEC, 0xD3, 0x02)           # role 1: PC2/PC3/PC5/PC6 in, PC6 NMI on
emit(0x3E, 0x02, 0x32, *word(0xC004))
label('mirror')
decode_echo()
emit(0xD3, 0x01); jr(0x18, 'mirror')

org(0x0400)
emit(*ENCODE)

for at, target in fixups:
    off = labels[target] - (at + 1)
    assert -128 <= off < 128, target
    prog[at] = off & 0xFF
rom = bytearray(32768)
for address, byte in prog.items(): rom[address] = byte
rom[0x7FF0:0x7FF8] = b'TMR SEGA'
rom[0x7FFF] = 0x6C                     # Game Gear export, 32 KB
open(sys.argv[1], 'wb').write(rom)
print(len(prog), 'bytes of code')
