/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <functional>

#include <EGL/egl.h>

#include <algorithm>
#include <cctype>
#include <string>
#include <utility>
#include <vector>
#include <unordered_set>

#include "libretrodroid.h"
#include "utils/libretrodroidexception.h"
#include "log.h"
#include "core.h"
#include "audio.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "netplay.h"
#include "netpacket.h"
#include "netplay_state_hash.h"
#include "state_hash_worker.h"
#include "rumble.h"
#include "shadermanager.h"
#include "environment.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/utils.h"
#include "utils/rect.h"
#include "errorcodes.h"
#include "vfs/vfs.h"

namespace libretrodroid {

uintptr_t LibretroDroid::callback_get_current_framebuffer() {
    return LibretroDroid::getInstance().handleGetCurrentFrameBuffer();
}

void LibretroDroid::callback_hw_video_refresh(
    const void *data,
    unsigned width,
    unsigned height,
    size_t pitch
) {
    LOGD("hw video refresh callback called %i %i", width, height);
    LibretroDroid::getInstance().handleVideoRefresh(data, width, height, pitch);
}

void LibretroDroid::callback_audio_sample(int16_t left, int16_t right) {
    LOGE("callback audio sample (left, right) has been called");
}

size_t LibretroDroid::callback_set_audio_sample_batch(const int16_t *data, size_t frames) {
    return LibretroDroid::getInstance().handleAudioCallback(data, frames);
}

void LibretroDroid::callback_retro_set_input_poll() {

}

int16_t LibretroDroid::callback_set_input_state(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    return LibretroDroid::getInstance().handleSetInputState(port, device, index, id);
}

void LibretroDroid::updateAudioSampleRateMultiplier() {
    if (audio) {
        audio->setPlaybackSpeed(frameSpeed);
    }
}

void LibretroDroid::resetGlobalVariables() {
    core = nullptr;
    audio = nullptr;
    video = nullptr;
    fpsSync = nullptr;
    input = nullptr;
    rumble = nullptr;
}

int LibretroDroid::availableDisks() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_num_images()
           : 0;
}

int LibretroDroid::currentDisk() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_image_index()
           : 0;
}

void LibretroDroid::changeDisk(unsigned int index) {
    if (Environment::getInstance().getRetroDiskControlCallback() == nullptr) {
        LOGE("Cannot swap disk. This platform does not support it.");
        return;
    }

    if (index < 0 || index >= Environment::getInstance().getRetroDiskControlCallback()->get_num_images()) {
        LOGE("Requested image index is not valid.");
        return;
    }

    if (Environment::getInstance().getRetroDiskControlCallback()->get_image_index() != index) {
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(true);
        Environment::getInstance().getRetroDiskControlCallback()->set_image_index((unsigned) index);
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(false);
    }
}

void LibretroDroid::updateVariable(const Variable& variable) {
    Environment::getInstance().updateVariable(variable.key, variable.value);
}

std::vector<Variable> LibretroDroid::getVariables() {
    return Environment::getInstance().getVariables();
}

std::vector<std::vector<struct Controller>> LibretroDroid::getControllers() {
    return Environment::getInstance().getControllers();
}

void LibretroDroid::setControllerType(unsigned int port, unsigned int type) {
    core->retro_set_controller_port_device(port, type);
}

bool LibretroDroid::unserializeState(int8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    return core->retro_unserialize(data, size);
}

bool LibretroDroid::unserializeSRAM(int8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t sramSize = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *sramState = core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);

    if (sramState == nullptr) {
        LOGE("Cannot load SRAM: nullptr in retro_get_memory_data");
        return false;
    }

    // Like RetroArch, load the prefix of a longer file: another core may append a footer to the
    // same cartridge RAM (mGBA stores the MBC3 clock after it; Gambatte keeps it apart).
    if (size > sramSize) {
        LOGE("SRAM file is %zu bytes longer than the core's; loading its first %zu", size - sramSize, sramSize);
        size = sramSize;
    }

    memcpy(sramState, data, size);

    return true;
}

std::pair<int8_t*, size_t> LibretroDroid::serializeSRAM() {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t size = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    auto* data = new int8_t[size];
    memcpy(data, (int8_t*) core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM), size);

    return std::pair(data, size);
}

void LibretroDroid::onSurfaceChanged(unsigned int width, unsigned int height) {
    LOGD("Performing libretrodroid onSurfaceChanged");
    video->updateScreenSize(width, height);
}

void LibretroDroid::onSurfaceCreated() {
    LOGD("Performing libretrodroid onSurfaceCreated");

    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    video = nullptr;

    Video::RenderingOptions renderingOptions {
        Environment::getInstance().isUseHwAcceleration(),
        system_av_info.geometry.base_width,
        system_av_info.geometry.base_height,
        Environment::getInstance().isUseDepth(),
        Environment::getInstance().isUseStencil(),
        openglESVersion,
        Environment::getInstance().getPixelFormat()
    };

    auto newVideo = new Video(
        renderingOptions,
        fragmentShaderConfig,
        Environment::getInstance().isBottomLeftOrigin(),
        Environment::getInstance().getScreenRotation(),
        skipDuplicateFrames,
        immersiveModeEnabled,
        viewportRect,
        immersiveModeConfig,
        viewportAlignment
    );

    video = std::unique_ptr<Video>(newVideo);

    if (Environment::getInstance().getHwContextReset() != nullptr) {
        Environment::getInstance().getHwContextReset()();
    }
}

void LibretroDroid::onMotionEvent(
    unsigned int port,
    unsigned int source,
    float xAxis,
    float yAxis
) {
    LOGD("Received motion event: %d %.2f, %.2f", source, xAxis, yAxis);
    if (input) {
        input->onMotionEvent(port, source, xAxis, yAxis);
    }
}

void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {
    LOGD("Received touch event: %.2f, %.2f", xAxis, yAxis);
    if (input && video) {
        auto [x, y] = video->getLayout().getRelativePosition(xAxis, yAxis);
        input->onMotionEvent(0, Input::MOTION_SOURCE_POINTER, x, y);
    }
}

#if defined(__ANDROID__)
void LibretroDroid::onKeyEvent(unsigned int port, int action, int keyCode) {
    LOGD("Received key event with action (%d) and keycode (%d)", action, keyCode);
    if (input) {
        input->onKeyEvent(port, action, keyCode);
    }
}
#endif

void LibretroDroid::onButton(unsigned int port, int retroId, bool pressed) {
    if (input) {
        input->onButton(port, retroId, pressed);
    }
}

void LibretroDroid::create(
    unsigned int GLESVersion,
    const std::string& soFilePath,
    const std::string& systemDir,
    const std::string& savesDir,
    std::vector<Variable> variables,
    const ShaderManager::Config& shaderConfig,
    float refreshRate,
    bool lowLatencyAudio,
    bool enableVirtualFileSystem,
    bool enableMicrophone,
    bool duplicateFrames,
    std::optional<ImmersiveMode::Config> immersiveModeConfig,
    const std::string& language
) {
    LOGD("Performing libretrodroid create");

    resetGlobalVariables();

    Environment::getInstance().initialize(systemDir, savesDir, &callback_get_current_framebuffer);
    Environment::getInstance().setLanguage(language);
    Environment::getInstance().setEnableVirtualFileSystem(enableVirtualFileSystem);
    Environment::getInstance().setEnableMicrophone(enableMicrophone);

    openglESVersion = GLESVersion;
    screenRefreshRate = refreshRate;
    skipDuplicateFrames = duplicateFrames;
    immersiveModeEnabled = GLESVersion >= 3 && immersiveModeConfig.has_value();
    this->immersiveModeConfig = immersiveModeConfig.value_or(ImmersiveMode::Config{});
    audioEnabled = true;
    frameSpeed = 1;

    core = std::make_unique<Core>(soFilePath);

    core->retro_set_video_refresh(&callback_hw_video_refresh);
    core->retro_set_environment(&Environment::callback_environment);
    core->retro_set_audio_sample(&callback_audio_sample);
    core->retro_set_audio_sample_batch(&callback_set_audio_sample_batch);
    core->retro_set_input_poll(&callback_retro_set_input_poll);
    core->retro_set_input_state(&callback_set_input_state);

    std::for_each(variables.begin(), variables.end(), [&](const Variable& v) {
        updateVariable(v);
    });

    core->retro_init();

    preferLowLatencyAudio = lowLatencyAudio;

    if (Environment::getInstance().isUseHwAcceleration() && openglESVersion < 3) {
        throw LibretroDroidError("OpenGL ES 3 is required for this Core", ERROR_GL_NOT_COMPATIBLE);
    }

    fragmentShaderConfig = shaderConfig;

    rumble = std::make_unique<Rumble>();
}

void LibretroDroid::loadGameFromPath(const std::string& gamePath) {
    LOGD("Performing libretrodroid loadGameFromPath");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(gamePath);
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(gamePath);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromBytes(const int8_t *data, size_t size) {
    LOGD("Performing libretrodroid loadGameFromBytes");

    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = nullptr;
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        game_info.data = data;
        game_info.size = size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles) {
    LOGD("Performing libretrodroid loadGameFromVirtualFiles");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    if (virtualFiles.empty()) {
        LOGE("Calling loadGameFromVirtualFiles without any file.");
        throw std::runtime_error("Calling loadGameFromVirtualFiles without any file.");
    }

    std::string firstFilePath = virtualFiles[0].getFileName();
    int firstFileFD = virtualFiles[0].getFD();

    bool loadUsingVFS = system_info.need_fullpath || virtualFiles.size() > 1;

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(firstFilePath);
    game_info.meta = nullptr;

    if (loadUsingVFS) {
        VFS::getInstance().initialize(std::move(virtualFiles));
    }

    if (loadUsingVFS) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(firstFileFD);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::destroy() {
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Performing libretrodroid destroy");

    if (Environment::getInstance().getHwContextDestroy() != nullptr) {
        Environment::getInstance().getHwContextDestroy()();
    }

    Netpacket::getInstance().clear();
    core->retro_unload_game();
    core->retro_deinit();

    video = nullptr;
    core = nullptr;
    rumble = nullptr;
    fpsSync = nullptr;
    audio = nullptr;

    Environment::getInstance().deinitialize();
    VFS::getInstance().deinitialize();
}

void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");

    input = std::make_unique<Input>();

    fpsSync->reset();
    audio->start();
    refreshAspectRatio();
}

void LibretroDroid::pause() {
    LOGD("Performing libretrodroid pause");
    audio->stop();

    input = nullptr;
}

void LibretroDroid::step() {
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Stepping into retro_run()");

    Netplay& netplay = Netplay::getInstance();
    if (netplay.isActive()) {

        if (fpsSync) fpsSync->advanceFrames();
        uint16_t localButtons = input ? input->joypadMask(0) : 0;
        if (netplay.isRollback()) {
            if (!stepNetplayRollback(localButtons)) {
                if (video && !video->rendersInVideoCallback()) video->renderFrame();
                return;
            }
        } else {
            uint32_t before = netplay.currentFrame();
            stepNetplayLockstep(localButtons);
            if (netplay.currentFrame() == before) {
                if (video && !video->rendersInVideoCallback()) video->renderFrame();
                return;
            }
        }
    } else {
        unsigned frames = 1;
        if (fpsSync) {
            unsigned requestedFrames = fpsSync->advanceFrames();

            frames = std::min(requestedFrames, 2u);
        }

        for (size_t i = 0; i < frames * frameSpeed; i++)
            core->retro_run();
    }

    if (video && !video->rendersInVideoCallback()) {
        video->renderFrame();
    }

    if (fpsSync) {
        fpsSync->wait();
    }

    if (rumble && rumbleEnabled) {
        rumble->fetchFromEnvironment();
    }

    if (video && Environment::getInstance().isGameGeometryUpdated()) {
        Environment::getInstance().clearGameGeometryUpdated();

        video->updateRendererSize(
            Environment::getInstance().getGameGeometryWidth(),
            Environment::getInstance().getGameGeometryHeight()
        );

        dirtyVideo = true;
    }

    if (video && Environment::getInstance().isScreenRotationUpdated()) {
        Environment::getInstance().clearScreenRotationUpdated();

        video->updateRotation(Environment::getInstance().getScreenRotation());
    }
}

float LibretroDroid::getAspectRatio() {
    if (aspectRatioOverride > 0) return aspectRatioOverride;
    float gameAspectRatio = Environment::getInstance().retrieveGameSpecificAspectRatio();
    return gameAspectRatio > 0 ? gameAspectRatio : defaultAspectRatio;
}

void LibretroDroid::refreshAspectRatio() {
    if (video) video->updateAspectRatio(getAspectRatio());
}

void LibretroDroid::setAspectRatioOverride(float ratio) {
    aspectRatioOverride = ratio;
    refreshAspectRatio();
}

void LibretroDroid::setRumbleEnabled(bool enabled) {
    rumbleEnabled = enabled;
}

bool LibretroDroid::isRumbleEnabled() const {
    return rumbleEnabled;
}

void LibretroDroid::setFrameSpeed(unsigned int speed) {
    frameSpeed = speed;
    updateAudioSampleRateMultiplier();
}

void LibretroDroid::setAudioEnabled(bool enabled) {
    audioEnabled = enabled;
}

void LibretroDroid::setShaderConfig(ShaderManager::Config shaderConfig) {
    fragmentShaderConfig = std::move(shaderConfig);
    if (video) {
        video->updateShaderType(fragmentShaderConfig);
    }
}

void LibretroDroid::handleVideoRefresh(
    const void *data,
    unsigned int width,
    unsigned int height,
    size_t pitch
) {
    if (netplayReplaying) return;
    if (video) {
        video->onNewFrame(data, width, height, pitch);

        if (video->rendersInVideoCallback()) {
            video->renderFrame();
        }
    }
}

size_t LibretroDroid::handleAudioCallback(const int16_t *data, size_t frames) {
    if (audio && audioEnabled && !netplayReplaying) {
        audio->write(data, frames);
    }
    return frames;
}

int16_t LibretroDroid::handleSetInputState(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    if (input) {
        return input->getInputState(port, device, index, id);
    }
    return 0;
}

uintptr_t LibretroDroid::handleGetCurrentFrameBuffer() {
    if (video) {
        return video->getCurrentFramebuffer();
    }
    return 0;
}

void LibretroDroid::reset() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_reset();
}

uint64_t LibretroDroid::hashStateLocked() {
    size_t size = core->retro_serialize_size();
    hashBuffer.resize(size);
    if (!core->retro_serialize(hashBuffer.data(), size)) return 0;
    return netplayStateHash(hashBuffer);
}

void LibretroDroid::startNetplay(int localPort, int players, int inputDelay, int hashInterval, bool rollback) {
    std::lock_guard<std::mutex> lock(coreLock);
    for (auto& snapshot : snapshots) snapshot.frame = UINT32_MAX;
    applyMultitapLocked(players >= 3);
    Netplay::getInstance().start(localPort, players, inputDelay, hashInterval, rollback);
}

std::string LibretroDroid::coreVersion() {
    if (!core) return "";
    struct retro_system_info info {};
    core->retro_get_system_info(&info);
    return info.library_version ? info.library_version : "";
}

void LibretroDroid::redraw() {
    std::lock_guard<std::mutex> lock(coreLock);
    if (!video) return;
    video->markDirty();
    video->renderFrame();
}

void LibretroDroid::renderTo(unsigned framebuffer, unsigned width, unsigned height) {
    std::lock_guard<std::mutex> lock(coreLock);
    if (video) video->renderTo(framebuffer, width, height);
}

bool LibretroDroid::setMultitap(bool enabled) {
    std::lock_guard<std::mutex> lock(coreLock);
    applyMultitapLocked(enabled);
    return multitapEnabled;
}

void LibretroDroid::stopNetplay() {
    std::lock_guard<std::mutex> lock(coreLock);
    Netplay::getInstance().stop();
    applyMultitapLocked(false);
}

void LibretroDroid::applyMultitapLocked(bool enabled) {
    if (enabled == multitapEnabled || !core) return;
    const auto& ports = Environment::getInstance().getControllers();
    if (!enabled) {
        core->retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
        multitapEnabled = false;
        LOGI("netplay: multitap desligado");
        return;
    }
    if (ports.size() < 2) return;
    for (const auto& controller : ports[1]) {
        std::string name = controller.description;
        std::transform(name.begin(), name.end(), name.begin(), ::tolower);
        if (name.find("multitap") == std::string::npos && name.find("multi-tap") == std::string::npos) continue;
        core->retro_set_controller_port_device(1, controller.id);
        multitapEnabled = true;
        LOGI("netplay: multitap ligado na porta 2 (%s, id=%u)", controller.description.c_str(), controller.id);
        return;
    }
    LOGI("netplay: core without multitap; only 2 controllers");
}

bool LibretroDroid::saveSnapshotLocked(uint32_t frame) {
    Snapshot& snapshot = snapshots[frame % SNAPSHOTS];
    size_t size = core->retro_serialize_size();
    snapshot.data.resize(size);
    bool ok = core->retro_serialize(snapshot.data.data(), size);
    snapshot.frame = ok ? frame : UINT32_MAX;
    return ok;
}

bool LibretroDroid::loadSnapshotLocked(uint32_t frame) {
    Snapshot& snapshot = snapshots[frame % SNAPSHOTS];
    return snapshot.frame == frame && core->retro_unserialize(snapshot.data.data(), snapshot.data.size());
}

void LibretroDroid::stepNetplayLockstep(uint16_t localButtons) {
    using std::chrono::steady_clock;
    using std::chrono::duration_cast;
    using std::chrono::microseconds;
    Netplay& netplay = Netplay::getInstance();
    auto t0 = steady_clock::now();
    if (!netplay.beginFrame(localButtons, std::chrono::milliseconds(8))) {
        netplay.recordVsync(false, duration_cast<microseconds>(steady_clock::now() - t0), microseconds(0));
        return;
    }
    auto t1 = steady_clock::now();
    uint32_t ranFrame = netplay.currentFrame();
    core->retro_run();
    netplay.recordVsync(true, duration_cast<microseconds>(t1 - t0), duration_cast<microseconds>(steady_clock::now() - t1));
    if (netplay.advance()) netplay.pushStateHash(ranFrame + 1, hashStateLocked());
}

bool LibretroDroid::stepNetplayRollback(uint16_t localButtons) {
    using std::chrono::steady_clock;
    using std::chrono::duration_cast;
    using std::chrono::microseconds;
    Netplay& netplay = Netplay::getInstance();
    auto t0 = steady_clock::now();
    Netplay::Plan plan = netplay.beginRollbackFrame(localButtons, std::chrono::milliseconds(8));
    auto t1 = steady_clock::now();
    if (!plan.run) {
        netplay.recordVsync(false, duration_cast<microseconds>(t1 - t0), microseconds(0));
        return false;
    }

    microseconds snapshotTime(0);
    auto snapshot = [&](uint32_t f) {
        auto start = steady_clock::now();
        saveSnapshotLocked(f);
        snapshotTime += duration_cast<microseconds>(steady_clock::now() - start);
    };

    uint32_t replayed = 0;
    if (plan.rollbackFrom != Netplay::NONE) {
        if (loadSnapshotLocked(plan.rollbackFrom)) {
            netplayReplaying = true;
            Environment::getInstance().setReplaying(true);
            for (uint32_t f = plan.rollbackFrom; f < plan.frame; f++) {

                netplay.prepareFrame(f);
                if (f != plan.rollbackFrom) snapshot(f);
                core->retro_run();
                replayed++;
            }
            netplayReplaying = false;
            Environment::getInstance().setReplaying(false);
        } else {

            LOGE("netplay: no snapshot of frame %u for rollback", plan.rollbackFrom);
        }
    }

    netplay.prepareFrame(plan.frame);
    snapshot(plan.frame);
    core->retro_run();
    netplay.advance();

    uint32_t hashFrame = netplay.takeConfirmedHashFrame();
    if (hashFrame != Netplay::NONE && snapshots[hashFrame % SNAPSHOTS].frame == hashFrame) {
        const auto& state = snapshots[hashFrame % SNAPSHOTS].data;
        static StateHashWorker hashWorker([](uint64_t generation, uint32_t f, uint64_t hash, const StateDiagnostic* blocks, size_t count) {
            Netplay::getInstance().publishStateCheck(generation, f, hash, blocks ? blocks->data() : nullptr, count);
        });
        hashWorker.submit(state, hashFrame, netplay.generationId(), hashFrame == 600);
    }
    netplay.recordVsync(true, duration_cast<microseconds>(t1 - t0),
                        duration_cast<microseconds>(steady_clock::now() - t1) - snapshotTime, replayed, snapshotTime);
    return true;
}

std::pair<int8_t*, size_t> LibretroDroid::serializeState() {
    std::lock_guard<std::mutex> lock(coreLock);

    Netplay& netplay = Netplay::getInstance();
    if (netplay.isActive() && netplay.isRollback() && netplay.currentFrame() > 0) {
        const Snapshot& last = snapshots[(netplay.currentFrame() - 1) % SNAPSHOTS];
        if (last.frame == netplay.currentFrame() - 1) {
            auto data = new int8_t[last.data.size()];
            memcpy(data, last.data.data(), last.data.size());
            return std::pair(data, last.data.size());
        }
    }

    size_t size = core->retro_serialize_size();
    auto data = new int8_t[size];

    core->retro_serialize(data, size);

    return std::pair(data, size);
}

void LibretroDroid::resetCheat() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_reset();
}

void LibretroDroid::setCheat(unsigned index, bool enabled, const std::string& code) {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_set(index, enabled, Utils::cloneToCString(code));
}

bool LibretroDroid::requiresVideoRefresh() const {
    return dirtyVideo;
}

void LibretroDroid::clearRequiresVideoRefresh() {
    dirtyVideo = false;
}

void LibretroDroid::afterGameLoad() {
    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    fpsSync = std::make_unique<FPSSync>(system_av_info.timing.fps, screenRefreshRate);

    double inputSampleRate = system_av_info.timing.sample_rate * fpsSync->getTimeStretchFactor();

    audio = std::make_unique<Audio>(
        (int32_t) std::lround(inputSampleRate),
        system_av_info.timing.fps,
        preferLowLatencyAudio
    );

    updateAudioSampleRateMultiplier();

    defaultAspectRatio = findDefaultAspectRatio(system_av_info);
}

float LibretroDroid::findDefaultAspectRatio(const retro_system_av_info& system_av_info) {
    float result = system_av_info.geometry.aspect_ratio;
    if (result < 0) {
        result =
            (float) system_av_info.geometry.base_width / (float) system_av_info.geometry.base_height;
    }
    return result;
}

void LibretroDroid::handleRumbleUpdates(const std::function<void(int, float, float)> &handler) {
    if (rumble && rumbleEnabled) {
        rumble->handleRumbleUpdates(handler);
    }
}

void LibretroDroid::setViewport(Rect viewportRect) {
    this->viewportRect = viewportRect;

    if (video != nullptr) {
        video->updateViewportSize(viewportRect);
    }
}

void LibretroDroid::setViewportAlignment(unsigned int viewportAlignment) {
    this-> viewportAlignment = viewportAlignment;

    if (video) {
        this->video->updateViewportAlignment(viewportAlignment);
    }
}

}
