/* GBA Multi-Pak test for 2-4 linked consoles (CC0). The parent sends 0x1000+n; each child sends 0xC000+id.
 * Results at 0x02000000: magic, id, good, bad, children present, last SIOMULTI0..3. Built by make_gba_link4_rom.py. */
typedef unsigned short u16;
typedef unsigned int u32;
#define REG16(a) (*(volatile u16*)(a))
#define SIOMULTI(i) REG16(0x04000120 + 2 * (i))
#define SIOCNT REG16(0x04000128)
#define SIOSEND REG16(0x0400012A)
#define RCNT REG16(0x04000134)
#define RESULT ((volatile u32*)0x02000000)
#define MAGIC 0x3450544C /* "LTP4" */

__attribute__((noreturn)) void start(void) {
	RESULT[0] = MAGIC;
	for (int i = 1; i < 12; ++i) RESULT[i] = 0;
	RCNT = 0;
	SIOCNT = 0x2000; /* MULTI, 9600 bps */
	u32 n = 0;
	for (;;) {
		for (volatile int d = 0; d < 2000; ++d) {}
		u16 cnt = SIOCNT;
		u32 id = (cnt >> 4) & 3;
		RESULT[1] = id;
		if (!(cnt & 4)) { /* SI low: parent */
			if (!(cnt & 8)) continue; /* SD: children ready */
			SIOSEND = (u16) (0x1000 + (n & 0xFFF));
			SIOCNT = 0x2000 | 0x80;
			while (SIOCNT & 0x80) {}
			u32 present = 0, bad = 0;
			for (u32 i = 1; i < 4; ++i) {
				u16 v = SIOMULTI(i);
				if (v == 0xFFFF) continue;
				if (v == 0xC000 + i) ++present; else ++bad;
			}
			if (SIOMULTI(0) != (u16) (0x1000 + (n & 0xFFF))) ++bad;
			RESULT[4] = present;
			if (bad) RESULT[3] += 1; else RESULT[2] += 1;
			++n;
		} else { /* child: its word must be in place before the parent starts */
			SIOSEND = (u16) (0xC000 + id);
			u16 before = SIOMULTI(0);
			u32 spins = 0;
			while (SIOMULTI(0) == before && spins < 200000) ++spins;
			u16 v = SIOMULTI(0);
			if (spins < 200000) {
				if ((v & 0xF000) == 0x1000 && SIOMULTI(id) == 0xC000 + id) RESULT[2] += 1; else RESULT[3] += 1;
			}
		}
		for (u32 i = 0; i < 4; ++i) RESULT[5 + i] = SIOMULTI(i);
	}
}
