#include "retroengine.h"

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

using namespace libretrodroid;

namespace {
std::string lastError;

struct Egl {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
} egl;

std::array<uint16_t, 4> buttons {};

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
        LibretroDroid::getInstance().create(
            3,
            config->core_path,
            config->system_dir,
            config->saves_dir,
            {},
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
}
