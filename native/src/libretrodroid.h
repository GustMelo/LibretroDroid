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

#ifndef LIBRETRODROID_LIBRETRODROID_H
#define LIBRETRODROID_LIBRETRODROID_H

#include <functional>

#include <EGL/egl.h>

#include <string>
#include <vector>
#include <unordered_set>
#include <array>
#include <mutex>
#include <memory>
#include <optional>

#include "log.h"
#include "core.h"
#include "audio.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "rumble.h"
#include "shadermanager.h"
#include "environment.h"
#include "rewind.h"
#include "vfs/vfsfile.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/rect.h"

namespace libretrodroid {
class LibretroDroid {
public:
    static LibretroDroid& getInstance()
    {
        static LibretroDroid instance;
        return instance;
    }
    LibretroDroid(LibretroDroid const&) = delete;
    void operator=(LibretroDroid const&) = delete;

    void setViewport(Rect viewportRect);

private:
    LibretroDroid() {}

public:
    void setCheat(unsigned index, bool enabled, const std::string& code);
    void resetCheat();

    std::pair<int8_t*, size_t> serializeState();
    bool unserializeState(int8_t *data, size_t size);

    std::pair<int8_t *, size_t> serializeSRAM();
    bool unserializeSRAM(int8_t *data, size_t size);

    // Consoles linked inside a retrolink core; 1 when the core has none.
    int linkMaxPlayers();
    bool linkSetPlayers(int count);
    void linkSetLocal(int player);
    void linkSetGrid(bool grid);
    bool linkLoadSave(int player, const int8_t *data, size_t size);
    bool linkKeep(int player);

    /** The running game reads e-Reader cards (it is the e-Reader cartridge, on a core that emulates its scanner). */
    bool ereaderSupported();
    /** Puts a card's dot code strip (.raw) in front of the e-Reader's scanner; false when the game has none. */
    bool ereaderScan(const int8_t *data, size_t size);

    void onSurfaceCreated();
    void onSurfaceChanged(unsigned int width, unsigned int height);

    void create(
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
    );
    void resume();
    void step();
    void pause();
    void destroy();

    void reset();

    void loadGameFromPath(const std::string &gamePath);
    void loadGameFromBytes(const int8_t *data, size_t size);
    void loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles);

#if defined(__ANDROID__)
    void onKeyEvent(unsigned int port, int action, int keyCode);
#endif
    void onButton(unsigned int port, int retroId, bool pressed);
    void onMotionEvent(unsigned int port, unsigned int source, float xAxis, float yAxis);
    void onTouchEvent(float xAxis, float yAxis);

    void refreshAspectRatio();
    float getAspectRatio();
    void setAspectRatioOverride(float ratio);

    bool requiresVideoRefresh() const;
    void clearRequiresVideoRefresh();

    std::vector<Variable> getVariables();
    void updateVariable(const Variable& variable);

    std::vector<std::vector<struct Controller>> getControllers();
    void setControllerType(unsigned int port, unsigned int type);

    int availableDisks();
    int currentDisk();
    void changeDisk(unsigned int index);

    void setRumbleEnabled(bool enabled);
    bool isRumbleEnabled() const;
    void handleRumbleUpdates(const std::function<void(int, float, float)> &handler);

    void setFrameSpeed(unsigned int speed);

    /**
     * Emulation speed: 1 is normal, 0.25..0.75 slow motion, above 1 fast-forward (up to [MAX_SPEED]x, bounded by
     * how many frames fit in a display refresh), 0 as fast as the device can. Audio follows up to [AUDIBLE_SPEED]x.
     */
    void setSpeed(float speed);
    /** Frames the core ran per displayed frame lately: what fast-forward actually reached. */
    float effectiveSpeed() const { return measuredSpeed; }

    /**
     * Run-ahead: shows the frame [frames] ahead of the one the console really is on, so a button shows its effect
     * that many frames sooner, removing the lag the original games had built in. 0 turns it off. Each displayed frame
     * then costs frames + 1 core runs plus a state save and load; off during netplay, link and rewinding.
     */
    void setRunAhead(unsigned frames);

    /** Records the last [budgetBytes] of play to step back through; 0 turns rewind off and frees it. */
    void setRewind(size_t budgetBytes);
    /** While true every displayed frame steps one recorded state back instead of playing. */
    void setRewinding(bool rewinding);
    /** Seconds of play the rewind history holds now. */
    float rewindSeconds() const;

    /** Sensors the core switched on (Sensors::Kind mask) and the latest phone readings for them. */
    uint32_t sensorsRequested() const;
    void setSensor(unsigned id, float value);

    void achievementsEnable(const std::string& userAgent, bool hardcore, bool unofficial);
    void achievementsDisable();
    void achievementsLogin(const std::string& username, const std::string& password, bool token);
    void achievementsLogout();
    void achievementsLoadGame(const std::string& path, uint32_t consoleId);
    void achievementsSetHardcore(bool enabled);
    bool achievementsHardcore();
    void achievementsHttpResponse(int64_t id, int status, const std::string& body);
    void achievementsIdle();
    std::string achievementsList();
    bool achievementsCanPause(uint32_t* framesRemaining);

    static constexpr float MAX_SPEED = 100.0f;
    static constexpr float AUDIBLE_SPEED = 4.0f;
    static constexpr unsigned MAX_RUN_AHEAD = 6;

    void setAudioEnabled(bool enabled);

    void setShaderConfig(ShaderManager::Config shaderConfig);

    void setViewportAlignment(unsigned int viewportAlignment);

    void resetGlobalVariables();

    void handleVideoRefresh(const void *data, unsigned width, unsigned height, size_t pitch);
    size_t handleAudioCallback(const int16_t* data, size_t frames);
    int16_t handleSetInputState(unsigned port, unsigned device, unsigned index, unsigned id);
    uintptr_t handleGetCurrentFrameBuffer();

private:
    void updateAudioSampleRateMultiplier();
    float findDefaultAspectRatio(const retro_system_av_info &system_av_info);
    void afterGameLoad();

protected:
    static void callback_hw_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch);
    static size_t callback_set_audio_sample_batch(const int16_t* data, size_t frames);
    static void callback_audio_sample(int16_t left, int16_t right);
    static int16_t callback_set_input_state(unsigned port, unsigned device, unsigned index, unsigned id);
    static uintptr_t callback_get_current_framebuffer();
    static void callback_retro_set_input_poll();

private:
    unsigned int frameSpeed = 1;
    float speed = 1.0f;
    double speedCredit = 0.0;
    float measuredSpeed = 1.0f;
    double frameCostUs = 0.0;
    void runFrames(unsigned displayFrames);
    void runCoreFrame(bool shown, bool audible);
    void afterCoreFrame();

    RewindBuffer rewind;
    size_t rewindBudget = 0;
    bool rewinding = false;
    unsigned rewindInterval = 1;
    unsigned rewindCountdown = 0;
    std::vector<uint8_t> rewindScratch;
    void captureRewindLocked();
    unsigned runAhead = 0;
    bool runAheadBroken = false;
    std::vector<uint8_t> runAheadState;
    bool runAheadAllowedLocked() const;
    void runAheadFrameLocked(bool audible);
    void pushRewindLocked(const uint8_t* state, size_t size);
    void stepRewindLocked();
    bool rewindAllowedLocked() const;
    double contentFps = 60.0;
    std::string libraryName;
    bool audioEnabled = true;
    bool preferLowLatencyAudio = false;
    bool rumbleEnabled = false;

    ShaderManager::Config fragmentShaderConfig = ShaderManager::Config {
        ShaderManager::Type::SHADER_DEFAULT, { }
    };

    Rect viewportRect = Rect(0.0F, 0.0F, 1.0F, 1.0F);
    unsigned int viewportAlignment = V_ALIGN_CENTER;
    float screenRefreshRate = 60.0;
    int openglESVersion = 2;
    bool skipDuplicateFrames = false;
    bool immersiveModeEnabled = false;
    ImmersiveMode::Config immersiveModeConfig {};

    float defaultAspectRatio = 1.0;
    float aspectRatioOverride = 0.0;
    bool dirtyVideo = false;

    std::mutex coreLock;

    uint64_t hashStateLocked();
    std::vector<uint8_t> hashBuffer;

    struct Snapshot {
        uint32_t frame = UINT32_MAX;
        std::vector<uint8_t> data;
    };
    static constexpr uint32_t SNAPSHOTS = 16;
    std::array<Snapshot, SNAPSHOTS> snapshots {};
    bool netplayReplaying = false;
    bool skipVideoFrame = false;
    bool skipAudioFrame = false;
    void stepNetplayLockstep(uint16_t localButtons);
    bool stepNetplayRollback(uint16_t localButtons);
    bool saveSnapshotLocked(uint32_t frame);
    bool loadSnapshotLocked(uint32_t frame);

public:
    void startNetplay(int localPort, int players, int inputDelay, int hashInterval, bool rollback);
    void stopNetplay();
    std::string coreVersion();
    void redraw();
    void renderTo(unsigned framebuffer, unsigned width, unsigned height);
    bool setMultitap(bool enabled);
private:
    void applyMultitapLocked(bool enabled);
    bool multitapEnabled = false;
public:
private:

    std::unique_ptr<Core> core;
    std::unique_ptr<Audio> audio;
    std::unique_ptr<Video> video;
    std::unique_ptr<FPSSync> fpsSync;
    std::unique_ptr<Input> input;
    std::unique_ptr<Rumble> rumble;
};
}

#endif
