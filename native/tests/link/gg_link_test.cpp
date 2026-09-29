// Usage: gg_link_test core_a core_b rom [plugged-late]. Two Genesis Plus GX link cores joined by an in-memory
// Netpacket cable run linktest.gg on one thread, like two devices; the second joins late so the first must wait.
// plugged-late: both play before the cable is plugged, as on devices, where pairing takes a moment.
#include <dlfcn.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <vector>
#include "../../third_party/libretro/libretro-common/include/libretro.h"
struct Core {
    void* h = nullptr; const retro_netpacket_callback* net = nullptr;
    std::deque<std::vector<unsigned char>> inbox; int repeated = 0; bool linked = false; uint16_t pixel = 0;
    void (*run)(); void* (*mem)(unsigned);
};
static Core cores[2];
static Core* cur;
static Core* peer() { return cur == &cores[0] ? &cores[1] : &cores[0]; }
static bool envcb(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE: cur->net = (const retro_netpacket_callback*)data; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char**)data = "/tmp"; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data = false; return true;
    default: return false;
    }
}
// Queued even before the partner starts, as the TCP connection buffers it.
static void send(int, const void* buf, size_t len, uint16_t) {
    peer()->inbox.emplace_back((const unsigned char*)buf, (const unsigned char*)buf + len);
}
static void deliver() {
    Core* c = cur;
    while (c->linked && !c->inbox.empty()) {
        auto packet = std::move(c->inbox.front()); c->inbox.pop_front();
        c->net->receive(packet.data(), packet.size(), c == &cores[0] ? 1 : 0);
    }
}
template<class T> void sym(void* h, T& f, const char* n) { f = (T)dlsym(h, n); if (!f) { printf("missing %s\n", n); exit(1); } }
static const char* romPath;
static void load(int i, const char* lib, std::vector<unsigned char>& rom) {
    Core& c = cores[i]; cur = &c; c.h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!c.h) { printf("%s\n", dlerror()); exit(1); }
    void (*env)(retro_environment_t); void (*init)(); bool (*loadGame)(const retro_game_info*);
    void (*vid)(retro_video_refresh_t); void (*audb)(retro_audio_sample_batch_t); void (*aud)(retro_audio_sample_t);
    void (*ip)(retro_input_poll_t); void (*is)(retro_input_state_t);
    sym(c.h, env, "retro_set_environment"); sym(c.h, init, "retro_init"); sym(c.h, loadGame, "retro_load_game");
    sym(c.h, c.run, "retro_run"); sym(c.h, c.mem, "retro_get_memory_data"); sym(c.h, vid, "retro_set_video_refresh");
    sym(c.h, aud, "retro_set_audio_sample"); sym(c.h, audb, "retro_set_audio_sample_batch");
    sym(c.h, ip, "retro_set_input_poll"); sym(c.h, is, "retro_set_input_state");
    env(envcb);
    vid([](const void* frame, unsigned w, unsigned h, size_t pitch) {   // RGB565: the backdrop at the centre
        if (!frame) cur->repeated++;
        else cur->pixel = *(const uint16_t*)((const char*)frame + h / 2 * pitch + w);
    });
    aud([](int16_t, int16_t) {}); audb([](const int16_t*, size_t n) { return n; });
    ip([] {}); is([](unsigned, unsigned, unsigned, unsigned) -> int16_t { return 0; });
    init();
    retro_game_info info{romPath, rom.data(), rom.size(), nullptr};
    if (!loadGame(&info) || !c.net) { printf("load failed or no Netpacket\n"); exit(2); }
    ((unsigned char*)c.mem(RETRO_MEMORY_SYSTEM_RAM))[0xF0] = i;   // role, before the ROM's first instruction
}
static void start(int i) {
    cur = &cores[i]; cores[i].linked = true;
    cores[i].net->start(i, send, deliver);
    if (!cores[i].net->connected || cores[i].net->connected(1 - i)) return;
    printf("core %d refused its peer\n", i); exit(3);
}
static void frame(int i) { cur = &cores[i]; deliver(); cores[i].run(); }
int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) return 64;
    const bool pluggedLate = argc == 5;
    romPath = argv[3];
    FILE* f = fopen(argv[3], "rb"); std::vector<unsigned char> rom(32768);
    if (!f || fread(rom.data(), 1, rom.size(), f) != rom.size()) return 66;
    fclose(f);
    load(0, argv[1], rom);
    int early = 1;
    if (pluggedLate) {
        load(1, argv[2], rom);
        for (int n = 0; n < 120; n++) { frame(0); frame(1); }
        start(0); start(1);
    } else {
        start(0);
        for (int n = 0; n < 20; n++) frame(0);   // the partner is not there yet: frames repeat
        early = cores[0].repeated;
        load(1, argv[2], rom);
        start(1);
    }
    for (int n = 0; n < 900; n++) { frame(0); frame(1); }
    auto* a = (unsigned char*)cores[0].mem(RETRO_MEMORY_SYSTEM_RAM);
    auto* b = (unsigned char*)cores[1].mem(RETRO_MEMORY_SYSTEM_RAM);
    printf("screen A=%04x B=%04x\n", cores[0].pixel, cores[1].pixel);
    printf("A serial=%d bad=%d rounds=%d phase=%d | B serial=%d bad=%d nmi=%d phase=%d | repeated A=%d (waiting %d) B=%d\n",
        a[0], a[1], a[2], a[4], b[0], b[1], b[3], b[4], cores[0].repeated, early, cores[1].repeated);
    bool ok = a[0] == 64 && a[1] == 0 && a[2] == 16 && a[4] == 2 && b[0] == 64 && b[1] == 0 && b[3] >= 16 &&
        early > 0 && cores[1].repeated == 0 &&
        cores[0].pixel == 0x001F && cores[1].pixel == 0x07E0;   // blue, green
    // The partner leaves: the remaining console must run on at full speed without repeating frames.
    cur = &cores[1]; cores[1].linked = false; cores[1].net->stop();
    cur = &cores[0]; cores[0].net->disconnected(1);
    int before = cores[0].repeated;
    auto t = std::chrono::steady_clock::now();
    for (int n = 0; n < 60; n++) frame(0);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t).count();
    printf("after disconnect: 60 frames in %lld ms, repeated %d\n", (long long)ms, cores[0].repeated - before);
    ok = ok && cores[0].repeated == before && ms < 400;
    puts(ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
