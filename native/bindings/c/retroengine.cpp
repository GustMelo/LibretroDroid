#include "retroengine.h"
#include "../../src/netpacket.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES3/gl3.h>

#include <unistd.h>

#include <array>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "libretrodroid.h"
#include "log.h"
#include "netplay.h"
#include "utils/rect.h"
#include "audio_tap.h"
#include "streamcapture.h"
#include "achievements.h"

using namespace libretrodroid;

namespace {
std::string lastError;

struct Egl {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
} egl;

std::array<uint16_t, 4> buttons {};

StreamCapture stream;

template <typename F>
bool guarded(const char *what, F &&body) {
    try {
        body();
        return true;
    } catch (const std::exception &e) {
        lastError = std::string(what) + ": " + e.what();
    } catch (...) {
        lastError = std::string(what) + ": unknown error";
    }
    LOGE("%s", lastError.c_str());
    return false;
}

uint8_t *copyOut(std::pair<int8_t *, size_t> data, size_t *size) {
    *size = data.second;
    auto *out = static_cast<uint8_t *>(std::malloc(data.second));
    if (out) std::memcpy(out, data.first, data.second);
    delete[] data.first;
    return out;
}
}

extern "C" {
const char *re_last_error(void) { return lastError.c_str(); }

bool re_attach_layer(void *layer) {
    const EGLAttrib displayAttributes[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE,
        EGL_NONE,
    };
    egl.display = eglGetPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, reinterpret_cast<void *>(EGL_DEFAULT_DISPLAY), displayAttributes);
    if (egl.display == EGL_NO_DISPLAY || !eglInitialize(egl.display, nullptr, nullptr)) {
        lastError = "EGL: no ANGLE/Metal display";
        return false;
    }
    const EGLint configAttributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(egl.display, configAttributes, &config, 1, &count) || count == 0) {
        lastError = "EGL: no RGBA8/ES3 config";
        return false;
    }
    egl.surface = eglCreateWindowSurface(egl.display, config, reinterpret_cast<EGLNativeWindowType>(layer), nullptr);
    const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    egl.context = eglCreateContext(egl.display, config, EGL_NO_CONTEXT, contextAttributes);
    if (egl.surface == EGL_NO_SURFACE || egl.context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)) {
        lastError = "EGL: failed to create surface/context (" + std::to_string(eglGetError()) + ")";
        return false;
    }
    eglSwapInterval(egl.display, 0);
    return true;
}

void re_detach_layer(void) {
    if (egl.display == EGL_NO_DISPLAY) return;
    eglMakeCurrent(egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (egl.context != EGL_NO_CONTEXT) eglDestroyContext(egl.display, egl.context);
    if (egl.surface != EGL_NO_SURFACE) eglDestroySurface(egl.display, egl.surface);
    eglTerminate(egl.display);
    egl = Egl {};
}

bool re_create(const re_config *config) {
    return guarded("create", [&] {
        buttons.fill(0);
        std::vector<Variable> variables;
        for (int i = 0; i < config->variable_count; i++) {
            if (config->variable_keys[i] && config->variable_values[i]) {
                variables.push_back(Variable { config->variable_keys[i], config->variable_values[i] });
            }
        }
        LibretroDroid::getInstance().create(
            3,
            config->core_path,
            config->system_dir,
            config->saves_dir,
            variables,
            ShaderManager::Config { static_cast<ShaderManager::Type>(config->shader), {} },
            config->refresh_rate,
            true,
            false,
            false,
            false,
            std::nullopt,
            config->language
        );
    });
}

bool re_load_game(const char *path) {
    return guarded("loadGame", [&] { LibretroDroid::getInstance().loadGameFromPath(path); });
}

bool re_surface_created(void) {
    return guarded("surfaceCreated", [] { LibretroDroid::getInstance().onSurfaceCreated(); });
}

void re_surface_changed(int width, int height) {
    guarded("surfaceChanged", [&] { LibretroDroid::getInstance().onSurfaceChanged(width, height); });
}

void re_resume(void) {
    buttons.fill(0);
    guarded("resume", [] { LibretroDroid::getInstance().resume(); });
}

void re_pause(void) { guarded("pause", [] { LibretroDroid::getInstance().pause(); }); }

void re_destroy(void) { guarded("destroy", [] { LibretroDroid::getInstance().destroy(); }); }

void re_frame(re_outgoing_fn outgoing, void *context) {
    LibretroDroid &engine = LibretroDroid::getInstance();
    guarded("step", [&] { engine.step(); });

    if (engine.requiresVideoRefresh()) {
        engine.clearRequiresVideoRefresh();
        engine.refreshAspectRatio();
    }
    for (const auto &item : Netplay::getInstance().drainOutbox()) {
        outgoing(context, static_cast<int>(item.type), item.frame, item.value);
    }
    eglSwapBuffers(egl.display, egl.surface);
}

void re_set_buttons(unsigned port, uint16_t mask) {
    if (port >= buttons.size()) return;
    uint16_t changed = buttons[port] ^ mask;
    for (int id = 0; id < 16; id++) {
        if (changed & (1u << id)) LibretroDroid::getInstance().onButton(port, id, (mask >> id) & 1);
    }
    buttons[port] = mask;
}

void re_set_viewport(float x, float y, float width, float height) {
    LibretroDroid::getInstance().setViewport(Rect(x, y, width, height));
}

float re_aspect_ratio(void) { return LibretroDroid::getInstance().getAspectRatio(); }

void re_set_aspect_ratio_override(float ratio) { LibretroDroid::getInstance().setAspectRatioOverride(ratio); }

void re_set_variable(const char *key, const char *value) {
    LibretroDroid::getInstance().updateVariable(Variable { key, value });
}

int csops(pid_t pid, unsigned int ops, void *useraddr, size_t usersize);

bool re_jit_available(void) {
    int flags = 0;
    constexpr unsigned int CS_OPS_STATUS = 0;
    constexpr int CS_DEBUGGED = 0x10000000;
    return csops(getpid(), CS_OPS_STATUS, &flags, sizeof(flags)) == 0 && (flags & CS_DEBUGGED) != 0;
}

size_t re_core_version(char *out, size_t capacity) {
    std::string version = LibretroDroid::getInstance().coreVersion();
    if (capacity > 0) {
        std::strncpy(out, version.c_str(), capacity - 1);
        out[capacity - 1] = '\0';
    }
    return version.size();
}

uint8_t *re_serialize(size_t *size) {
    uint8_t *result = nullptr;
    *size = 0;
    guarded("serialize", [&] { result = copyOut(LibretroDroid::getInstance().serializeState(), size); });
    return result;
}

bool re_unserialize(const uint8_t *data, size_t size) {
    bool ok = false;
    guarded("unserialize", [&] {
        ok = LibretroDroid::getInstance().unserializeState(const_cast<int8_t *>(reinterpret_cast<const int8_t *>(data)), size);
    });
    return ok;
}

uint8_t *re_serialize_sram(size_t *size) {
    uint8_t *result = nullptr;
    *size = 0;
    guarded("serializeSRAM", [&] { result = copyOut(LibretroDroid::getInstance().serializeSRAM(), size); });
    return result;
}

int re_link_max_players(void) { return LibretroDroid::getInstance().linkMaxPlayers(); }

bool re_link_set_players(int count) { return LibretroDroid::getInstance().linkSetPlayers(count); }

void re_link_set_local(int player) { LibretroDroid::getInstance().linkSetLocal(player); }

void re_link_set_grid(bool grid) { LibretroDroid::getInstance().linkSetGrid(grid); }

bool re_link_keep(int player) { return LibretroDroid::getInstance().linkKeep(player); }

bool re_ereader_supported(void) { return LibretroDroid::getInstance().ereaderSupported(); }

bool re_ereader_scan(const uint8_t *data, size_t size) {
    return LibretroDroid::getInstance().ereaderScan(reinterpret_cast<const int8_t *>(data), size);
}

bool re_link_load_save(int player, const uint8_t *data, size_t size) {
    return LibretroDroid::getInstance().linkLoadSave(player, (const int8_t *) data, size);
}

bool re_unserialize_sram(const uint8_t *data, size_t size) {
    bool ok = false;
    guarded("unserializeSRAM", [&] {
        ok = LibretroDroid::getInstance().unserializeSRAM(const_cast<int8_t *>(reinterpret_cast<const int8_t *>(data)), size);
    });
    return ok;
}

void re_free(void *data) { std::free(data); }

void re_reset(void) { guarded("reset", [] { LibretroDroid::getInstance().reset(); }); }

void re_redraw(void) {
    if (guarded("redraw", [] { LibretroDroid::getInstance().redraw(); })) eglSwapBuffers(egl.display, egl.surface);
}

uint8_t *re_capture(int *width, int *height) {
    *width = 0;
    *height = 0;
    EGLint w = 0, h = 0;
    eglQuerySurface(egl.display, egl.surface, EGL_WIDTH, &w);
    eglQuerySurface(egl.display, egl.surface, EGL_HEIGHT, &h);
    if (w <= 0 || h <= 0 || !guarded("capture", [] { LibretroDroid::getInstance().redraw(); })) return nullptr;
    const size_t row = static_cast<size_t>(w) * 4;
    auto *pixels = static_cast<uint8_t *>(std::malloc(row * h));
    if (!pixels) return nullptr;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    std::vector<uint8_t> line(row);
    for (EGLint y = 0; y < h / 2; y++) {
        uint8_t *top = pixels + y * row;
        uint8_t *bottom = pixels + (h - 1 - y) * row;
        std::memcpy(line.data(), top, row);
        std::memcpy(top, bottom, row);
        std::memcpy(bottom, line.data(), row);
    }
    eglSwapBuffers(egl.display, egl.surface);
    *width = w;
    *height = h;
    return pixels;
}

int re_disk_count(void) {
    int count = 0;
    guarded("availableDisks", [&] { count = LibretroDroid::getInstance().availableDisks(); });
    return count;
}

int re_disk_current(void) {
    int index = 0;
    guarded("currentDisk", [&] { index = LibretroDroid::getInstance().currentDisk(); });
    return index;
}

void re_disk_set(unsigned index) { guarded("changeDisk", [&] { LibretroDroid::getInstance().changeDisk(index); }); }

void re_netplay_start(int local_port, int players, int input_delay, int hash_interval, bool rollback) {
    LibretroDroid::getInstance().startNetplay(local_port, players, input_delay, hash_interval, rollback);
}

void re_netplay_stop(void) { LibretroDroid::getInstance().stopNetplay(); }

void re_netplay_set_input(int port, uint32_t frame, uint16_t buttons) {
    Netplay::getInstance().setRemote(port, frame, buttons);
}

void re_netpacket_set_transport(void *context, re_netpacket_send_fn send) {
    libretrodroid::Netpacket::getInstance().setTransport(
        context,
        reinterpret_cast<libretrodroid::Netpacket::TransportSend>(send));
}

bool re_netpacket_start(uint16_t client_id) {
    return libretrodroid::Netpacket::getInstance().start(client_id);
}

void re_netpacket_receive(const void *data, size_t size, uint16_t client_id) {
    libretrodroid::Netpacket::getInstance().receive(data, size, client_id);
}

void re_netpacket_poll(void) { libretrodroid::Netpacket::getInstance().poll(); }

void re_netpacket_stop(void) { libretrodroid::Netpacket::getInstance().stop(); }

void re_netpacket_set_poll(re_netpacket_poll_fn poll) {
    libretrodroid::Netpacket::getInstance().setPoll(poll);
}
bool re_netpacket_connected(uint16_t id) {
    return libretrodroid::Netpacket::getInstance().connected(id);
}
void re_netpacket_disconnected(uint16_t id) {
    libretrodroid::Netpacket::getInstance().disconnected(id);
}

void re_set_motion(unsigned port, int source, float x, float y) {
    if (port >= buttons.size() || source < 0 || source > 2) return;
    LibretroDroid::getInstance().onMotionEvent(port, source, x, y);
}

bool re_set_multitap(bool enabled) {
    bool active = false;
    guarded("multitap", [&] { active = LibretroDroid::getInstance().setMultitap(enabled); });
    return active;
}

void re_stream_frame(int width, int height, re_video_fn video, void *context) {
    if (!video) return;
    guarded("stream", [&] {
        stream.capture(width, height, [&](const uint8_t *pixels, int w, int h) { video(context, pixels, w, h); });
    });
}

void re_stream_stop(void) { stream.release(); }

void re_set_audio_tap(re_audio_fn audio, void *context) {
    setAudioTap(audio, context);
    if (!audio) setAudioTapOnly(false);
}
void re_set_audio_tap_only(bool tap_only) { setAudioTapOnly(tap_only); }

void re_set_speed(float speed) { LibretroDroid::getInstance().setSpeed(speed); }

float re_effective_speed(void) { return LibretroDroid::getInstance().effectiveSpeed(); }

void re_set_run_ahead(unsigned frames) { LibretroDroid::getInstance().setRunAhead(frames); }

void re_set_audio_volume(float volume) { LibretroDroid::getInstance().setAudioVolume(volume); }

void re_set_vsync(bool enabled) { LibretroDroid::getInstance().setVSync(enabled); }

void re_set_frame_skip(bool enabled) { LibretroDroid::getInstance().setFrameSkip(enabled); }

void re_set_rewind(size_t budget_bytes) { LibretroDroid::getInstance().setRewind(budget_bytes); }

void re_set_rewinding(bool rewinding) { LibretroDroid::getInstance().setRewinding(rewinding); }

float re_rewind_seconds(void) { return LibretroDroid::getInstance().rewindSeconds(); }

uint32_t re_sensors_requested(void) { return LibretroDroid::getInstance().sensorsRequested(); }

void re_set_sensor(unsigned id, float value) { LibretroDroid::getInstance().setSensor(id, value); }

void re_set_rumble_enabled(bool enabled) { LibretroDroid::getInstance().setRumbleEnabled(enabled); }

void re_poll_rumble(re_rumble_fn rumble, void *context) {
    LibretroDroid::getInstance().handleRumbleUpdates([&](int port, float weak, float strong) {
        rumble(context, port, weak, strong);
    });
}

void re_cheat_reset(void) { guarded("resetCheat", [] { LibretroDroid::getInstance().resetCheat(); }); }

void re_cheat_set(unsigned index, bool enabled, const char *code) {
    guarded("setCheat", [&] { LibretroDroid::getInstance().setCheat(index, enabled, code ? code : ""); });
}

void re_set_shader(int shader, const char *params) {
    ShaderManager::Config config { static_cast<ShaderManager::Type>(shader), {} };
    std::string text = params ? params : "";
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find(';', start);
        if (end == std::string::npos) end = text.size();
        std::string pair = text.substr(start, end - start);
        size_t equals = pair.find('=');
        if (equals != std::string::npos && equals > 0) config.params[pair.substr(0, equals)] = pair.substr(equals + 1);
        start = end + 1;
    }
    LibretroDroid::getInstance().setShaderConfig(config);
}

static char *copyString(const std::string &text) {
    auto *out = static_cast<char *>(std::malloc(text.size() + 1));
    if (out) std::memcpy(out, text.c_str(), text.size() + 1);
    return out;
}

char *re_variables_json(void) {
    std::string out = "[";
    bool first = true;
    for (const auto &variable : LibretroDroid::getInstance().getVariables()) {
        if (!first) out += ",";
        first = false;
        out += "{\"key\":" + json::quote(variable.key) + ",\"value\":" + json::quote(variable.value) +
               ",\"description\":" + json::quote(variable.description) + "}";
    }
    out += "]";
    return copyString(out);
}

void re_ra_enable(const char *user_agent, bool hardcore, bool unofficial) {
    LibretroDroid::getInstance().achievementsEnable(user_agent ? user_agent : "", hardcore, unofficial);
}

void re_ra_disable(void) { LibretroDroid::getInstance().achievementsDisable(); }

void re_ra_login(const char *username, const char *secret, bool is_token) {
    LibretroDroid::getInstance().achievementsLogin(username ? username : "", secret ? secret : "", is_token);
}

void re_ra_logout(void) { LibretroDroid::getInstance().achievementsLogout(); }

void re_ra_load_game(const char *path, uint32_t console_id) {
    LibretroDroid::getInstance().achievementsLoadGame(path ? path : "", console_id);
}

void re_ra_set_hardcore(bool enabled) { LibretroDroid::getInstance().achievementsSetHardcore(enabled); }

bool re_ra_hardcore(void) { return LibretroDroid::getInstance().achievementsHardcore(); }

void re_ra_http_response(int64_t id, int status, const char *body, size_t length) {
    LibretroDroid::getInstance().achievementsHttpResponse(id, status, body ? std::string(body, length) : std::string());
}

void re_ra_idle(void) { LibretroDroid::getInstance().achievementsIdle(); }

char *re_ra_list(void) { return copyString(LibretroDroid::getInstance().achievementsList()); }

bool re_ra_can_pause(uint32_t *frames_remaining) { return LibretroDroid::getInstance().achievementsCanPause(frames_remaining); }

char *re_ra_events(void) {
    auto events = Achievements::getInstance().drainEvents();
    if (events.empty()) return nullptr;
    std::string out = "[";
    for (size_t i = 0; i < events.size(); i++) {
        if (i) out += ",";
        out += events[i];
    }
    out += "]";
    return copyString(out);
}
}
