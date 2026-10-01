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

package com.swordfish.libretrodroid;

import java.util.List;

public class LibretroDroid {
    static {
        System.loadLibrary("libretrodroid");
    }

    public static final int MOTION_SOURCE_DPAD = 0;
    public static final int MOTION_SOURCE_ANALOG_LEFT = 1;
    public static final int MOTION_SOURCE_ANALOG_RIGHT = 2;
    public static final int MOTION_SOURCE_POINTER = 3;

    public static final int SHADER_DEFAULT = 0;
    public static final int SHADER_CRT = 1;
    public static final int SHADER_LCD = 2;
    public static final int SHADER_SHARP = 3;
    public static final int SHADER_UPSCALE_CUT = 4;
    public static final int SHADER_UPSCALE_CUT2 = 5;
    public static final int SHADER_UPSCALE_CUT3 = 6;
    public static final int SHADER_RETRO = 7;

    public static final String SHADER_UPSCALE_CUT_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_VALUE = "EDGE_MIN_VALUE";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_CONTRAST = "EDGE_MIN_CONTRAST";

    public static final String SHADER_UPSCALE_CUT2_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING = "SOFT_EDGES_SHARPENING";
    public static final String SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING_AMOUNT = "SOFT_EDGES_SHARPENING_AMOUNT";
    public static final String SHADER_UPSCALE_CUT2_PARAM_HARD_EDGES_SEARCH_MAX_ERROR = "HARD_EDGES_SEARCH_MAX_ERROR";

    public static final String SHADER_UPSCALE_CUT3_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING = "SOFT_EDGES_SHARPENING";
    public static final String SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING_AMOUNT = "SOFT_EDGES_SHARPENING_AMOUNT";
    public static final String SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_ERROR = "HARD_EDGES_SEARCH_MAX_ERROR";
    public static final String SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_DISTANCE = "HARD_EDGES_SEARCH_MAX_DISTANCE";

    public static final int ERROR_LOAD_LIBRARY = 0;
    public static final int ERROR_LOAD_GAME = 1;
    public static final int ERROR_GL_NOT_COMPATIBLE = 2;
    public static final int ERROR_SERIALIZATION = 3;
    public static final int ERROR_CHEAT = 4;
    public static final int ERROR_GENERIC = -1;

    public static native void create(
        int GLESVersion,
        String coreFilePath,
        String systemDir,
        String savesDir,
        Variable[] variables,
        GLRetroShader shaderConfig,
        float refreshRate,
        boolean preferLowLatencyAudio,
        boolean enableVirtualFileSystem,
        boolean enableMicrophone,
        boolean skipDuplicateFrames,
        ImmersiveMode immersiveMode,
        String language
    );

    public static native void loadGameFromPath(String gameFilePath);
    public static native void loadGameFromBytes(byte[] gameFileBytes);
    public static native void loadGameFromVirtualFiles(List<DetachedVirtualFile> virtualFiles);
    public static native void resume();

    public static native void onSurfaceCreated();
    public static native void onSurfaceChanged(int width, int height);

    public static native void pause();
    public static native void destroy();

    public static native void step(GLRetroView retroView);

    /** GL thread: renders the frame once more at width x height and writes the previous one (RGBA, top row first). */
    public static native boolean streamFrame(int width, int height, java.nio.ByteBuffer buffer);
    /** GL thread: frees what streamFrame allocated. */
    public static native void streamStop();
    /** Taps the audio as played, for readStreamAudio. */
    public static native void setStreamAudio(boolean enabled);
    /** While the audio is tapped: true plays silence here, so the sound is heard only where it streams to. */
    public static native void setStreamAudioOnly(boolean enabled);
    /** 48 kHz interleaved 16-bit stereo into a direct buffer; returns the frames written. */
    public static native int readStreamAudio(java.nio.ByteBuffer buffer, int frames);

    public interface NetpacketCallbacks {
        void send(int flags, byte[] data, int target);
        void pollReceive();
    }
    public static native boolean startNetpacket(int localId, NetpacketCallbacks callbacks);
    public static native boolean connectNetpacket(int peerId);
    public static native void receiveNetpacket(byte[] data, int size, int sender);
    public static native void pollNetpacket();
    public static native void stopNetpacket();

    public static native void startNetplay(int localPort, int players, int inputDelay, int hashInterval, boolean rollback);
    public static native void stopNetplay();
    public static native void redraw();
    public static native void setNetplayInput(int port, int frame, int buttons);
    public static native int netplayFrame();
    public static native int netplayStalls();
    public static native String coreVersion();

    public static native void reset();

    public static native void setRumbleEnabled(boolean enabled);

    /** 1 normal, 0.1..0.99 slow motion, above 1 fast-forward (up to 100x), 0 as fast as the device can. */
    public static native void setSpeed(float speed);
    /** Frames run per displayed frame lately; negative while rewinding. */
    public static native float effectiveSpeed();

    /** Keeps the last budgetBytes of play for rewinding; 0 turns rewind off. */
    public static native void setRewind(long budgetBytes);
    public static native void setRewinding(boolean rewinding);
    public static native float rewindSeconds();

    public static final int SENSOR_ACCELEROMETER = 1;
    public static final int SENSOR_GYROSCOPE = 2;
    public static final int SENSOR_ILLUMINANCE = 4;
    /** SENSOR_* mask of what the core switched on. */
    public static native int sensorsRequested();
    /** Libretro sensor id: accelerometer x/y/z 0-2 (m/s2), gyroscope x/y/z 3-5 (rad/s), illuminance 6 (lux). */
    public static native void setSensor(int id, float value);

    /** Core options as UTF-8 JSON: [{key, value, description}], description being "Label; value1|value2|...". */
    public static native byte[] variablesJson();

    public static native void achievementsEnable(String userAgent, boolean hardcore, boolean unofficial);
    public static native void achievementsDisable();
    public static native void achievementsLogin(String username, String secret, boolean isToken);
    public static native void achievementsLogout();
    public static native void achievementsLoadGame(String path, int consoleId);
    public static native void achievementsSetHardcore(boolean enabled);
    public static native boolean achievementsHardcore();
    public static native void achievementsHttpResponse(long id, int status, byte[] body);
    public static native void achievementsIdle();
    /** UTF-8 JSON of the loaded game's achievements, grouped in buckets. */
    public static native byte[] achievementsList();
    /** 0 when the game may pause now, otherwise the frames hardcore still asks to play first. */
    public static native int achievementsPauseWait();
    /** Pending events as a UTF-8 JSON array, or null. */
    public static native byte[] achievementsEvents();
    public static native void setFrameSpeed(int speed);
    public static native void setAudioEnabled(boolean enabled);
    public static native void setShaderConfig(GLRetroShader shader);
    public static native void setViewport(float x, float y, float width, float height);
    public static native void setViewportAlignment(int viewportAlignment);

    public static native byte[] serializeState();
    public static native boolean unserializeState(byte[] state);

    public static native void setCheat(int index, boolean enable, String code);
    public static native void resetCheat();

    public static native byte[] serializeSRAM();
    public static native boolean unserializeSRAM(byte[] sram);

    // Consoles linked inside a retrolink core, console p on controller port p. Max is 1 without one.
    public static native int linkMaxPlayers();
    public static native boolean linkSetPlayers(int count);
    public static native void linkSetLocal(int player);
    public static native void linkSetGrid(boolean grid);
    public static native boolean linkLoadSave(int player, byte[] save);
    public static native boolean linkKeep(int player);

    public static native void updateVariable(Variable variable);
    public static native Variable[] getVariables();

    public static native int availableDisks();
    public static native int currentDisk();
    public static native void changeDisk(int index);

    public static native void onMotionEvent(int port, int motionSource, float xAxis, float yAxis);
    public static native void onTouchEvent(float xAxis, float yAxis);

    public static native void onKeyEvent(int port, int action, int keyCode);

    public static native void refreshAspectRatio();
    public static native void setAspectRatioOverride(float ratio);

    public static native Controller[][] getControllers();
    public static native void setControllerType(int port, int type);
}
