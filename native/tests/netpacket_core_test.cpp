// Smoke test of the real core ABI. Arguments: libretro dylib/so, diagnostic ROM.
#include "../src/netpacket.h"
#include <cassert>
#include <dlfcn.h>
#include <fstream>
#include <iterator>
#include <vector>
using libretrodroid::Netpacket;
static bool registered;
static bool environment(unsigned command, void* data) {
    if (command == RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE) {
        auto* callbacks = static_cast<retro_netpacket_callback*>(data);
        assert(callbacks && callbacks->start && callbacks->receive && callbacks->protocol_version);
        Netpacket::getInstance().setCoreInterface(callbacks);
        registered = true;
        return true;
    }
    if (command == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT) return true;
    if (command == RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY ||
        command == RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY) {
        *static_cast<const char**>(data) = "/tmp";
        return true;
    }
    if (command == RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE) {
        *static_cast<bool*>(data) = false;
        return true;
    }
    return false;
}
static void video(const void*, unsigned, unsigned, size_t) {}
static size_t audio(const int16_t*, size_t n) { return n; }
static void inputPoll() {}
static int16_t input(unsigned, unsigned, unsigned, unsigned) { return 0; }
static void send(void*, int, const void*, size_t, uint16_t, bool) {}
static void poll(void*) {}
template<class F> F symbol(void* lib, const char* name) {
    auto result = reinterpret_cast<F>(dlsym(lib, name));
    assert(result);
    return result;
}
int main(int argc, char** argv) {
    assert(argc == 3);
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(lib);
    symbol<void(*)(retro_environment_t)>(lib, "retro_set_environment")(environment);
    symbol<void(*)(retro_video_refresh_t)>(lib, "retro_set_video_refresh")(video);
    symbol<void(*)(retro_audio_sample_batch_t)>(lib, "retro_set_audio_sample_batch")(audio);
    symbol<void(*)(retro_input_poll_t)>(lib, "retro_set_input_poll")(inputPoll);
    symbol<void(*)(retro_input_state_t)>(lib, "retro_set_input_state")(input);
    symbol<void(*)()>(lib, "retro_init")();
    std::ifstream file(argv[2], std::ios::binary);
    std::vector<char> rom((std::istreambuf_iterator<char>(file)), {});
    assert(!rom.empty());
    retro_game_info game {argv[2], rom.data(), rom.size(), nullptr};
    assert(symbol<bool(*)(const retro_game_info*)>(lib, "retro_load_game")(&game));
    assert(registered);
    auto& bridge = Netpacket::getInstance();
    bridge.setTransport(nullptr, send);
    bridge.setPoll(poll);
    assert(bridge.start(0));
    bridge.poll();
    bridge.stop();
    bridge.clear();
    symbol<void(*)()>(lib, "retro_unload_game")();
    symbol<void(*)()>(lib, "retro_deinit")();
    dlclose(lib);
    bridge.clear(); // Must never dereference callbacks from the unloaded dylib.
}
