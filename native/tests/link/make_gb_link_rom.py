# Minimal Game Boy serial link test. Both devices run the same ROM: each waits as slave for a random
# time, then drives the clock itself. Every transfer sends 0x5A; receiving 0x5A (not the 0xFF of an
# unplugged cable) 16 times turns the screen black. Without a partner the screen stays white.
prog = []; labels = {}; fixups = []
def emit(*b): prog.extend(b)
def label(n): labels[n] = len(prog)
def jr(op, target): emit(op, 0); fixups.append((len(prog) - 1, target))
emit(0xF3)                      # di
emit(0x31, 0xFE, 0xFF)          # ld sp,$FFFE
emit(0xAF)                      # xor a
emit(0xEA, 0x00, 0xC0)          # ld [$C000],a
emit(0xE0, 0x47)                # ldh [$47],a   BGP white
label('main')
emit(0x3E, 0x5A, 0xE0, 0x01)    # ld a,$5A ; ldh [SB],a
emit(0x3E, 0x80, 0xE0, 0x02)    # ld a,$80 ; ldh [SC],a  (slave)
emit(0xF0, 0x04, 0xE6, 0x3F, 0xC6, 0x10, 0x47)  # ldh a,[DIV]; and $3F; add $10; ld b,a
label('outer')
emit(0x0E, 0x00)                # ld c,0
label('inner')
emit(0xF0, 0x02, 0xCB, 0x7F)    # ldh a,[SC]; bit 7,a
jr(0x28, 'done')                # jr z,done
emit(0x0D); jr(0x20, 'inner')   # dec c; jr nz,inner
emit(0x05); jr(0x20, 'outer')   # dec b; jr nz,outer
emit(0x3E, 0x5A, 0xE0, 0x01)    # master: ld a,$5A ; ldh [SB],a
emit(0x3E, 0x81, 0xE0, 0x02)    # ld a,$81 ; ldh [SC],a
label('mwait')
emit(0xF0, 0x02, 0xCB, 0x7F); jr(0x20, 'mwait')
label('done')
emit(0xF0, 0x01, 0xFE, 0x5A); jr(0x20, 'main')   # ldh a,[SB]; cp $5A; jr nz,main
emit(0xFA, 0x00, 0xC0, 0x3C, 0xEA, 0x00, 0xC0)   # ld a,[$C000]; inc a; ld [$C000],a
emit(0xFE, 0x10); jr(0x38, 'main')               # cp 16; jr c,main
emit(0x3E, 0xFF, 0xE0, 0x47)                     # BGP black: linked
jr(0x18, 'main')
for at, target in fixups:
    off = labels[target] - (at + 1)
    assert -128 <= off < 128
    prog[at] = off & 0xFF
rom = bytearray(32768)
rom[0x100:0x104] = bytes([0x00, 0xC3, 0x50, 0x01])
rom[0x104:0x134] = bytes.fromhex('CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E')
rom[0x134:0x13C] = b'LINKTEST'
rom[0x150:0x150 + len(prog)] = bytes(prog)
x = 0
for b in rom[0x134:0x14D]: x = (x - b - 1) & 0xFF
rom[0x14D] = x
g = sum(rom) & 0xFFFF
rom[0x14E] = g >> 8; rom[0x14F] = g & 0xFF
open(__import__('sys').argv[1], 'wb').write(rom)
print(len(prog), 'bytes of code')
