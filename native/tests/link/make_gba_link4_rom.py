#!/usr/bin/env python3
"""Builds gba_link4.c into a 32 KiB GBA image with clang alone: no ARM linker is needed because the
program is one position-independent ARM function with no relocations, placed right after the header."""
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

source = Path(__file__).with_name('gba_link4.c')
with tempfile.TemporaryDirectory() as work:
    obj = Path(work) / 'link4.o'
    subprocess.check_call(['clang', '--target=armv4t-none-eabi', '-marm', '-O2', '-ffreestanding', '-fno-builtin',
                           '-nostdlib', '-fno-pic', '-fno-jump-tables', '-c', str(source), '-o', str(obj)])
    elf = obj.read_bytes()
shoff = struct.unpack_from('<I', elf, 0x20)[0]
entsize, count, names = struct.unpack_from('<HHH', elf, 0x2E)
sections = [struct.unpack_from('<IIIIIIIIII', elf, shoff + i * entsize) for i in range(count)]
strtab = sections[names][4]
name = lambda offset: elf[strtab + offset:elf.index(b'\0', strtab + offset)].decode()
text = None
for section in sections:
    section_name = name(section[0])
    if section_name == '.rel.text' or (section_name in ('.data', '.bss', '.rodata') and section[5]):
        sys.exit(f'{section_name} would need a linker')
    if section_name == '.text':
        text = elf[section[4]:section[4] + section[5]]
rom = bytearray(b'\xff' * 32768)
rom[0:4] = struct.pack('<I', 0xEA000000 | ((0xC0 - 8) // 4))  # b 0x080000C0
rom[4:0xA0] = bytes(0x9C)
rom[0xA0:0xB2] = b'LINKTEST4P\0\0LTP400'
rom[0xB2] = 0x96
rom[0xB3:0xBD] = bytes(10)
checksum = 0
for byte in rom[0xA0:0xBD]:
    checksum = (checksum - byte) & 0xFF
rom[0xBD] = (checksum - 0x19) & 0xFF
rom[0xBE:0xC0] = bytes(2)
rom[0xC0:0xC0 + len(text)] = text
Path(sys.argv[1]).write_bytes(rom)
