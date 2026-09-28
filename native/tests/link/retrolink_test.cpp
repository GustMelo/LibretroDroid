// retrolink test: usage retrolink_test coreA coreB rom players gb|gba4. Runs N linked consoles in one
// mGBA core, checks each console's link results and that a second instance that joined from the first's
// state stays byte-identical, which is what netplay relies on.
#include <dlfcn.h>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "../../third_party/libretro/libretro-common/include/libretro.h"
struct Core {
    void* h; uint16_t pad[4] = {0, 0, 0, 0};
    bool (*load)(const retro_game_info*); void (*run)(); void* (*mem)(unsigned); size_t (*ssize)();
    bool (*ser)(void*, size_t); bool (*unser)(const void*, size_t);
    bool (*setPlayers)(unsigned); void (*setLocal)(unsigned); bool (*loadSave)(unsigned, const void*, size_t);
    bool (*keep)(unsigned);
};
static Core* cur;
static void logf_(retro_log_level l, const char* f, ...) { if (l < RETRO_LOG_WARN) return; va_list a; va_start(a, f); vfprintf(stderr, f, a); va_end(a); }
static bool env(unsigned cmd, void* d) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((retro_log_callback*)d)->log = logf_; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: case RETRO_ENVIRONMENT_SET_GEOMETRY: return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)d = true; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
    default: return false;
    }
}
template<class T> void sym(void* h, T& f, const char* n) { f = (T)dlsym(h, n); if (!f) { printf("missing %s\n", n); exit(1); } }
static Core open(const char* lib, std::vector<unsigned char>& rom) {
    Core c; c.h = dlopen(lib, RTLD_NOW | RTLD_LOCAL); cur = &c;
    void (*se)(retro_environment_t); void (*init)(); void (*vid)(retro_video_refresh_t); void (*ab)(retro_audio_sample_batch_t);
    void (*a)(retro_audio_sample_t); void (*ip)(retro_input_poll_t); void (*is)(retro_input_state_t);
    sym(c.h, se, "retro_set_environment"); sym(c.h, init, "retro_init"); sym(c.h, c.load, "retro_load_game");
    sym(c.h, c.run, "retro_run"); sym(c.h, c.mem, "retro_get_memory_data"); sym(c.h, vid, "retro_set_video_refresh");
    sym(c.h, a, "retro_set_audio_sample"); sym(c.h, ab, "retro_set_audio_sample_batch"); sym(c.h, ip, "retro_set_input_poll");
    sym(c.h, is, "retro_set_input_state"); sym(c.h, c.ssize, "retro_serialize_size"); sym(c.h, c.ser, "retro_serialize");
    sym(c.h, c.unser, "retro_unserialize"); sym(c.h, c.setPlayers, "retro_link_set_players"); sym(c.h, c.setLocal, "retro_link_set_local");
    sym(c.h, c.loadSave, "retro_link_load_save"); sym(c.h, c.keep, "retro_link_keep");
    se(env); vid([](const void*, unsigned, unsigned, size_t) {}); a([](int16_t, int16_t) {}); ab([](const int16_t*, size_t n) { return n; });
    ip([] {}); is([](unsigned port, unsigned dev, unsigned, unsigned id) -> int16_t {
        if (dev != RETRO_DEVICE_JOYPAD || port > 3) return 0;
        return id == RETRO_DEVICE_ID_JOYPAD_MASK ? cur->pad[port] : (cur->pad[port] >> id) & 1; });
    init();
    retro_game_info info{"game", rom.data(), rom.size(), nullptr};
    if (!c.load(&info)) { printf("load failed\n"); exit(2); }
    return c;
}
static void frames(Core& c, int n, unsigned seed) {
    cur = &c;
    for (int f = 0; f < n; f++) {
        for (int p = 0; p < 4; p++) c.pad[p] = (uint16_t)(((seed + f * 7 + p * 13) % 31 == 0) ? (1 << RETRO_DEVICE_ID_JOYPAD_A) : 0);
        c.run();
    }
}
static std::vector<char> state(Core& c) { cur = &c; std::vector<char> s(c.ssize()); if (!c.ser(s.data(), s.size())) { printf("serialize failed\n"); exit(3); } return s; }
static uint32_t rd32(Core& c, unsigned p, size_t off) { cur = &c; c.setLocal(p); uint32_t v; memcpy(&v, (char*)c.mem(RETRO_MEMORY_SYSTEM_RAM) + off, 4); return v; }
int main(int argc, char** argv) {
    if (argc != 6) { printf("usage: rltest coreA coreB rom players gb|gba\n"); return 64; }
    FILE* f = fopen(argv[3], "rb"); std::vector<unsigned char> rom; int ch; while ((ch = fgetc(f)) != EOF) rom.push_back(ch); fclose(f);
    unsigned players = atoi(argv[4]); bool gba = !strcmp(argv[5], "gba") || !strcmp(argv[5], "gba4");
    Core a = open(argv[1], rom);
    frames(a, 30, 1);
    cur = &a; if (!a.setPlayers(players)) { printf("set_players failed\n"); return 4; }
    // The netplay host canonicalizes: serialize, load its own state, send that state.
    auto join = state(a); cur = &a; a.unser(join.data(), join.size());
    Core b = open(argv[2], rom);
    cur = &b; if (!b.unser(join.data(), join.size())) { printf("join unserialize failed\n"); return 5; }
    int n = getenv("FRAMES") ? atoi(getenv("FRAMES")) : 900;
    frames(a, n, 7); frames(b, n, 7);
    auto sa = state(a), sb = state(b);
    bool same = sa == sb;
    printf("players=%u state=%zu bytes identical=%s\n", players, sa.size(), same ? "yes" : "NO");
    int fails = same ? 0 : 1;
    for (unsigned p = 0; p < players; p++) {
        if (!strcmp(argv[5], "gba4")) {
            uint32_t magic = rd32(a, p, 0), id = rd32(a, p, 4), good = rd32(a, p, 8), bad = rd32(a, p, 12), present = rd32(a, p, 16);
            printf("  console %u: magic=%08x id=%u good=%u bad=%u present=%u multi=%04x %04x %04x %04x\n", p, magic, id, good, bad, present,
                rd32(a, p, 20) & 0xFFFF, rd32(a, p, 24) & 0xFFFF, rd32(a, p, 28) & 0xFFFF, rd32(a, p, 32) & 0xFFFF);
            if (magic != 0x3450544C || good < 20 || bad || (id == 0 && present != players - 1)) fails = 1;
        } else if (gba) {
            uint32_t magic = rd32(a, p, 0), status = rd32(a, p, 8), id = rd32(a, p, 12), part = rd32(a, p, 20), tr = rd32(a, p, 24), err = rd32(a, p, 32), to = rd32(a, p, 48);
            printf("  console %u: magic=%08x status=%08x id=%u participants=%u transfers=%u errors=%u timeouts=%u\n", p, magic, status, id, part, tr, err, to);
            if (magic != 0x31544B4C || (status & 0x80000000u) || !tr || err) fails = 1;
        } else {
            uint32_t v = rd32(a, p, 0) & 0xFF;
            printf("  console %u: received 0x5A %u times\n", p, v);
            if (v < 16) fails = 1;
        }
    }
    // The session ends on B: its last console goes on alone as console 0, RAM and all.
    std::vector<char> before(64);
    cur = &b; b.setLocal(players - 1); memcpy(before.data(), b.mem(RETRO_MEMORY_SYSTEM_RAM), before.size());
    if (!b.keep(players - 1) || memcmp(before.data(), b.mem(RETRO_MEMORY_SYSTEM_RAM), before.size()) || b.ssize() >= sb.size()) {
        printf("  keep: console %u did not become the only console\n", players - 1);
        fails = 1;
    }
    frames(b, 30, 3);
    printf(fails ? "FAIL\n" : "PASS\n");
    return fails;
}
