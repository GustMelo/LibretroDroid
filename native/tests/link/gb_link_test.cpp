// Usage: gb_link_test core_a core_b rom. Two Gambatte instances linked over 127.0.0.1, each running linktest.gb in its own thread.
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <map>
#include <thread>
#include <vector>
#include "../../third_party/libretro/libretro-common/include/libretro.h"
struct Core {
    void* h; std::map<std::string,std::string> vars; bool dirty = false;
    void (*init)(); bool (*load)(const retro_game_info*); void (*run)();
    void* (*mem)(unsigned); void (*env)(retro_environment_t);
    void (*vid)(retro_video_refresh_t); void (*aud)(retro_audio_sample_t); void (*audb)(retro_audio_sample_batch_t);
    void (*ip)(retro_input_poll_t); void (*is)(retro_input_state_t);
};
static thread_local Core* cur;
#include <cstdarg>
static void logf_(retro_log_level, const char* f, ...) { va_list a; va_start(a, f); vfprintf(stderr, f, a); va_end(a); }
static bool envcb(unsigned cmd, void* data) {
    if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE) {
        auto* v = (retro_variable*)data; auto it = cur->vars.find(v->key);
        v->value = it == cur->vars.end() ? nullptr : it->second.c_str(); return it != cur->vars.end();
    }
    if (cmd == RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE) { *(bool*)data = cur->dirty; cur->dirty = false; return true; }
    if (cmd == RETRO_ENVIRONMENT_GET_LOG_INTERFACE) {
        ((retro_log_callback*)data)->log = logf_;
        return true;
    }
    if (cmd == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT) return true;
    if (cmd == RETRO_ENVIRONMENT_GET_CAN_DUPE) { *(bool*)data = true; return true; }
    return false;
}
template<class T> void sym(void* h, T& f, const char* n) { f = (T)dlsym(h, n); if (!f) { printf("missing %s\n", n); exit(1); } }
static void runCore(const char* lib, bool server, int frames, std::vector<unsigned char>* rom, int* result) {
    Core c; cur = &c; c.h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    sym(c.h, c.env, "retro_set_environment"); sym(c.h, c.init, "retro_init"); sym(c.h, c.load, "retro_load_game");
    sym(c.h, c.run, "retro_run"); sym(c.h, c.mem, "retro_get_memory_data"); sym(c.h, c.vid, "retro_set_video_refresh");
    sym(c.h, c.aud, "retro_set_audio_sample"); sym(c.h, c.audb, "retro_set_audio_sample_batch");
    sym(c.h, c.ip, "retro_set_input_poll"); sym(c.h, c.is, "retro_set_input_state");
    c.env(envcb); c.vid([](const void*, unsigned, unsigned, size_t) {}); c.aud([](int16_t, int16_t) {});
    c.audb([](const int16_t*, size_t n) { return n; }); c.ip([] {}); c.is([](unsigned, unsigned, unsigned, unsigned) -> int16_t { return 0; });
    c.init();
    retro_game_info info{"linktest.gb", rom->data(), rom->size(), nullptr};
    if (!c.load(&info)) { printf("load failed\n"); exit(2); }
    c.vars["gambatte_gb_link_network_port"] = "56400";
    const char* ip = "127000000001";
    for (int i = 0; i < 12; i++) c.vars["gambatte_gb_link_network_server_ip_" + std::to_string(i + 1)] = std::string(1, ip[i]);
    c.vars["gambatte_gb_link_mode"] = server ? "Network Server" : "Network Client";
    c.dirty = true;
    for (int f = 0; f < frames; f++) { c.run(); std::this_thread::sleep_for(std::chrono::milliseconds(16)); }
    unsigned char* r = (unsigned char*)c.mem(RETRO_MEMORY_SYSTEM_RAM);
    *result = r[0];
}
int main(int argc, char** argv) {
    if (argc != 4) return 64;
    FILE* f = fopen(argv[3], "rb"); std::vector<unsigned char> rom(32768); fread(rom.data(), 1, rom.size(), f); fclose(f);
    int a = -1, b = -1;
    std::thread s(runCore, argv[1], true, 600, &rom, &a);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    std::thread cl(runCore, argv[2], false, 600, &rom, &b);
    s.join(); cl.join();
    printf("server count=%d client count=%d\n", a, b);
    return a >= 16 && b >= 16 ? 0 : 1;
}
