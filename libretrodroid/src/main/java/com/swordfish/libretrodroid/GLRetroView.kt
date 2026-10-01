/*
 *     Copyright (C) 2022  Filippo Scognamiglio
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

package com.swordfish.libretrodroid

import com.libretrodroid.netplay.NetpacketSession
import com.libretrodroid.netplay.TcpConnection
import com.libretrodroid.netplay.HostStart
import com.libretrodroid.netplay.NetplayEmulator
import com.libretrodroid.netplay.NetplayListener

import android.app.ActivityManager
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.coroutineScope
import com.swordfish.libretrodroid.KtUtils.awaitUninterruptibly
import com.swordfish.libretrodroid.gamepad.GamepadsManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.properties.Delegates

class GLRetroView(
    context: Context,
    private val data: GLRetroViewData,
) : GLSurfaceView(context), LifecycleObserver {

    @Volatile private var linkSession: NetpacketSession? = null
    val isLinkActive: Boolean get() = linkSession != null

    /** Connection must already have completed compatibility negotiation. */
    fun startLink(connection: TcpConnection, localId: Int): Boolean =
        runOnEmulationThread(true) {
            check(linkSession == null && isEmulationReady)
            stopNetplay()
            frameSpeed = 1
            val session = NetpacketSession(connection, AndroidNetpacketCore(), localId)
            if (session.start()) { linkSession = session; true } else false
        }

    fun stopLink() = runOnEmulationThread(true) { stopLinkOnEmulationThread() }

    /** Called once from the emulation thread with the reason when a running link session ends by itself. */
    var onLinkEnded: ((String) -> Unit)? = null

    /** Changes core options from the emulation thread; blocks until applied, so call it off the UI thread. */
    fun setVariables(vararg variables: Variable) = runOnEmulationThread(true) {
        variables.forEach { LibretroDroid.updateVariable(it) }
    }

    private fun stopLinkOnEmulationThread() {
        linkSession?.stopOnEmulationThread()
        linkSession = null
    }

    var audioEnabled: Boolean by Delegates.observable(true) { _, _, value ->
        LibretroDroid.setAudioEnabled(value)
    }

    var frameSpeed: Int by Delegates.vetoable(1) { _, _, value ->
        if (isLinkActive && value != 1) false
        else { LibretroDroid.setFrameSpeed(value); true }
    }

    /**
     * Emulation speed: 1 normal, 0.1..0.99 slow motion, above 1 fast-forward up to 100x (as many frames as fit in a
     * display refresh), 0 as fast as the device can. A link cable session plays only at 1.
     */
    var speed: Float by Delegates.vetoable(1f) { _, _, value ->
        if (isLinkActive && value != 1f) false
        else { LibretroDroid.setSpeed(value); true }
    }

    /** Frames the core ran per displayed frame lately (what fast-forward reached); negative while rewinding. */
    val effectiveSpeed: Float get() = LibretroDroid.effectiveSpeed()

    /** Run-ahead frames (0..6): the picture shows that many frames ahead, removing the games' own input lag. */
    var runAhead: Int by Delegates.observable(0) { _, _, value -> LibretroDroid.setRunAhead(value) }

    /** How loud the game plays on this device, 0 (silent) to 1; a stream to another screen keeps its full volume. */
    var audioVolume: Float by Delegates.observable(1f) { _, _, value -> LibretroDroid.setAudioVolume(value) }

    /** False paces frames by the clock instead of the display's refresh, even when both rates match. */
    var vsync: Boolean by Delegates.observable(true) { _, _, value -> LibretroDroid.setVSync(value) }

    /** Whether a late frame is made up by running two and drawing the second (only when the clock paces frames). */
    var frameSkip: Boolean by Delegates.observable(true) { _, _, value -> LibretroDroid.setFrameSkip(value) }

    /** Keeps the last [budgetBytes] of play to rewind through; 0 turns rewind off and frees it. */
    fun setRewind(budgetBytes: Long) = LibretroDroid.setRewind(budgetBytes)

    /** While true each displayed frame steps back through the recorded play instead of advancing. */
    var rewinding: Boolean by Delegates.observable(false) { _, _, value -> LibretroDroid.setRewinding(value) }

    /** Seconds of play the rewind history holds now. */
    val rewindSeconds: Float get() = LibretroDroid.rewindSeconds()

    /** Sensors the core switched on, as [LibretroDroid.SENSOR_ACCELEROMETER] and friends. Cheap: poll it. */
    val sensorsRequested: Int get() = LibretroDroid.sensorsRequested()

    /** Latest reading of libretro sensor [id]: accelerometer 0-2 (m/s²), gyroscope 3-5 (rad/s), light 6 (lux). */
    fun setSensor(id: Int, value: Float) = LibretroDroid.setSensor(id, value)

    /** Core options as JSON: [{key, value, description}], description being "Label; value1|value2|...". */
    fun coreOptionsJson(): String = runOnEmulationThread(true) { LibretroDroid.variablesJson().decodeToString() }

    /** RetroAchievements inside the engine. Events (including the HTTP calls to make) arrive on [achievementEvents]. */
    fun achievementsEnable(userAgent: String, hardcore: Boolean, unofficial: Boolean) =
        LibretroDroid.achievementsEnable(userAgent, hardcore, unofficial)

    fun achievementsDisable() = LibretroDroid.achievementsDisable()

    fun achievementsLogin(username: String, secret: String, isToken: Boolean) =
        LibretroDroid.achievementsLogin(username, secret, isToken)

    fun achievementsLogout() = LibretroDroid.achievementsLogout()

    /** After the game is running: identifies [path] for RetroAchievements console [consoleId] and loads its set. */
    fun achievementsLoadGame(path: String, consoleId: Int) = LibretroDroid.achievementsLoadGame(path, consoleId)

    fun achievementsSetHardcore(enabled: Boolean) = LibretroDroid.achievementsSetHardcore(enabled)

    val achievementsHardcore: Boolean get() = LibretroDroid.achievementsHardcore()

    /** Answers an "http" event; [status] <= 0 when the request never reached the server. */
    fun achievementsHttpResponse(id: Long, status: Int, body: ByteArray) = LibretroDroid.achievementsHttpResponse(id, status, body)

    /** Keeps RetroAchievements' network queue moving while the game is paused. */
    fun achievementsIdle() = LibretroDroid.achievementsIdle()

    fun achievementsListJson(): String = LibretroDroid.achievementsList().decodeToString()

    /** 0 when the game may pause now; otherwise frames hardcore asks to play first. */
    val achievementsPauseWait: Int get() = LibretroDroid.achievementsPauseWait()

    /** Takes pending events as a JSON array, or null; thread-safe, for polling while the game is paused. */
    fun drainAchievementEvents(): String? = LibretroDroid.achievementsEvents()?.decodeToString()

    private val achievementEventsSubject = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** JSON arrays of RetroAchievements events, emitted after the frame that raised them. */
    val achievementEvents: Flow<String> get() = achievementEventsSubject

    var shader: ShaderConfig by Delegates.observable(data.shader) { _, _, value ->
        LibretroDroid.setShaderConfig(buildShader(value))
    }

    var viewport: RectF by Delegates.observable(RectF(0f, 0f, 1f, 1f)) { _, _, value ->
        runOnEmulationThread(true) {
            LibretroDroid.setViewport(value.left, value.top, value.width(), value.height())
        }
    }

    var aspectRatioOverride: Float by Delegates.observable(0f) { _, _, value ->
        runOnEmulationThread(true) { LibretroDroid.setAspectRatioOverride(value) }
    }

    var viewportAlignment: ViewportAlignment by Delegates.observable(ViewportAlignment.CENTER) { _, _, value ->
        LibretroDroid.setViewportAlignment(value.value)
    }

    private val openGLESVersion: Int

    private var isGameLoaded = false
    private var isEmulationReady = false
    private var isAborted = false

    private val retroGLEventsSubject = MutableSharedFlow<GLRetroEvents>(1)
    private val retroGLIssuesErrors = MutableSharedFlow<Int>(1)

    private val rumbleEventsSubject = MutableSharedFlow<RumbleEvent>()

    private var lifecycle: Lifecycle? = null

    init {
        openGLESVersion = getGLESVersion(context)
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(openGLESVersion)
        setRenderer(Renderer())
        keepScreenOn = true
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate(lifecycleOwner: LifecycleOwner) = catchExceptions {
        lifecycle = lifecycleOwner.lifecycle
        LibretroDroid.create(
            openGLESVersion,
            data.coreFilePath,
            data.systemDirectory,
            data.savesDirectory,
            data.variables,
            buildShader(data.shader),
            getDefaultRefreshRate(),
            data.preferLowLatencyAudio,
            data.gameVirtualFiles.isNotEmpty(),
            data.enableMicrophone,
            data.skipDuplicateFrames,
            data.immersiveMode,
            getDeviceLanguage()
        )
        LibretroDroid.setRumbleEnabled(data.rumbleEventsEnabled)
        LibretroDroid.setViewportAlignment(data.viewportAlignment.value)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() = catchExceptions {
        linkSession?.closeNetwork()
        linkSession = null
        streamSink = null
        LibretroDroid.setStreamAudio(false)
        LibretroDroid.destroy()
        lifecycle = null
    }

    private fun getDeviceLanguage() = Locale.getDefault().language

    private fun getDefaultRefreshRate(): Float {
        return (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
    }

    fun sendKeyEvent(action: Int, keyCode: Int, port: Int = 0) {
        queueEvent { LibretroDroid.onKeyEvent(port, action, keyCode) }
    }

    fun sendMotionEvent(source: Int, xAxis: Float, yAxis: Float, port: Int = 0) {
        queueEvent { LibretroDroid.onMotionEvent(port, source, xAxis, yAxis) }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val position = when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                normalizeTouchCoordinates(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                TOUCH_EVENT_OUTSIDE
            }

            else -> null
        }

        if (position != null) {
            LibretroDroid.onTouchEvent(position.x, position.y)
        }

        return true
    }

    private fun clamp(x: Float, min: Float, max: Float) = minOf(maxOf(x, min), max)

    private fun normalizeTouchCoordinates(x: Float, y: Float): PointF {
        val x = clamp(2f * x / width - 1f, -1f, +1f)
        val y = clamp(2f * y / height - 1f, -1f, +1f)
        return PointF(x, y)
    }

    fun serializeState(useEmulationThread: Boolean = true): ByteArray {
        return runOnEmulationThread(useEmulationThread) {
            check(!isLinkActive) { "Disconnect link before saving state" }
            LibretroDroid.serializeState()
        }
    }

    /** Replaces every cheat with [codes], in order, in one emulation-thread hop; empty removes them all. */
    fun setCheats(codes: List<String>) = runOnEmulationThread(true) {
        LibretroDroid.resetCheat()
        codes.forEachIndexed { index, code -> LibretroDroid.setCheat(index, true, code) }
    }

    /** The running game is the e-Reader and can scan cards. */
    val ereaderSupported: Boolean get() = runOnEmulationThread(true) { LibretroDroid.ereaderSupported() }

    /** Puts a card's dot code strip (.raw) in front of the e-Reader; false when the game has no scanner. */
    fun ereaderScan(card: ByteArray): Boolean = runOnEmulationThread(true) { LibretroDroid.ereaderScan(card) }

    /** Rumble events on [getRumbleEvents]: on while a game asks for them and the player wants them. */
    fun setRumbleEnabled(enabled: Boolean) = LibretroDroid.setRumbleEnabled(enabled)

    fun setCheat(index: Int, enable: Boolean, code: String, useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) {
            LibretroDroid.setCheat(index, enable, code)
        }
    }

    fun unserializeState(data: ByteArray, useEmulationThread: Boolean = true): Boolean {
        return runOnEmulationThread(useEmulationThread) {
            if (isLinkActive) return@runOnEmulationThread false
            LibretroDroid.unserializeState(data)
        }
    }

    fun serializeSRAM(useEmulationThread: Boolean = true): ByteArray {
        return runOnEmulationThread(useEmulationThread) {
            LibretroDroid.serializeSRAM()
        }
    }

    fun unserializeSRAM(data: ByteArray, useEmulationThread: Boolean = true): Boolean {
        return runOnEmulationThread(useEmulationThread) {
            if (isLinkActive) return@runOnEmulationThread false
            LibretroDroid.unserializeSRAM(data)
        }
    }

    fun reset(useEmulationThread: Boolean = true) = runOnEmulationThread(useEmulationThread) {
        check(!isLinkActive) { "Disconnect link before reset" }
        LibretroDroid.reset()
    }

    fun getGLRetroEvents(): Flow<GLRetroEvents> {
        return retroGLEventsSubject
    }

    fun getGLRetroErrors(): Flow<Int> {
        return retroGLIssuesErrors
    }

    fun getRumbleEvents(): Flow<RumbleEvent> {
        return rumbleEventsSubject
    }

    @Volatile
    var netplayListener: NetplayListener? = null

    val coreVersion: String get() = LibretroDroid.coreVersion()

    fun netplayEmulator(rollback: Boolean, recompilerOption: String? = null, minInputDelay: Int = 0): NetplayEmulator = object : NetplayEmulator {
        override val rollback = rollback
        override val minInputDelay = minInputDelay
        override val canRecompile = recompilerOption != null
        override fun setRecompiler(enabled: Boolean) {
            val key = recompilerOption ?: return
            runOnEmulationThread(true) { LibretroDroid.updateVariable(Variable(key, if (enabled) "enabled" else "disabled")) }
        }
        override fun startAsHost(players: Int, inputDelay: Int) = startNetplayAsHost(players, inputDelay, rollback)
        override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?) =
            startNetplayAsClient(port, players, inputDelay, rollback, state, saveRam)
        override fun stop() = stopNetplay()
        override fun pushInput(port: Int, frame: Int, buttons: Int) = pushNetplayInput(port, frame, buttons)
        override fun reset() = this@GLRetroView.reset()
        override val linkMaxPlayers: Int get() = this@GLRetroView.linkMaxPlayers
        override fun linkConsoles(players: Int, saves: Map<Int, ByteArray>, rebuild: Boolean) = runOnEmulationThread(true) {
            if (rebuild) LibretroDroid.linkSetPlayers(1)
            LibretroDroid.linkSetPlayers(players)
            saves.forEach { (port, save) -> LibretroDroid.linkLoadSave(port, save) }
        }
        override fun linkConsoleSave(port: Int): ByteArray? = runOnEmulationThread(true) {
            LibretroDroid.linkSetLocal(port)
            LibretroDroid.serializeSRAM().also { LibretroDroid.linkSetLocal(linkLocal) }
        }
        override fun setLinkLocal(port: Int) = runOnEmulationThread(true) {
            linkLocal = port
            LibretroDroid.linkSetLocal(port)
        }
        override fun linkLocalSave(): ByteArray? = runOnEmulationThread(true) { LibretroDroid.serializeSRAM() }
        override fun linkKeepLocal() = runOnEmulationThread(true) {
            LibretroDroid.linkKeep(linkLocal)
            linkLocal = 0
        }
    }

    /** Consoles the core links inside itself (retrolink): 1 for an ordinary core. */
    val linkMaxPlayers: Int by lazy { runOnEmulationThread(true) { LibretroDroid.linkMaxPlayers() } }

    @Volatile private var linkLocal = 0

    /**
     * Players sharing this device on a retrolink core: [players] linked consoles, controller port p on console p,
     * shown side by side (a 2x2 grid for 3 or 4). 1 goes back to a single console.
     */
    fun setLocalLinkPlayers(players: Int): Boolean = runOnEmulationThread(true) {
        val linked = LibretroDroid.linkSetPlayers(players)
        LibretroDroid.linkSetGrid(linked && players > 1)
        linked
    }

    fun startNetplayAsHost(players: Int, inputDelay: Int, rollback: Boolean, hashInterval: Int = DEFAULT_HASH_INTERVAL): HostStart =
        runOnEmulationThread(true) {
            check(!isLinkActive) { "Link and controller netplay are mutually exclusive" }
            LibretroDroid.startNetplay(0, players, inputDelay, hashInterval, rollback)
            val state = LibretroDroid.serializeState()
            // Linked consoles: loading the state rebuilds the cable, exactly as on every joining device.
            if (LibretroDroid.linkMaxPlayers() > 1) LibretroDroid.unserializeState(state)
            HostStart(state, LibretroDroid.serializeSRAM().takeIf { it.isNotEmpty() })
        }

    private var ownSaveRam: ByteArray? = null

    fun startNetplayAsClient(
        localPort: Int,
        players: Int,
        inputDelay: Int,
        rollback: Boolean,
        state: ByteArray,
        saveRam: ByteArray?,
        hashInterval: Int = DEFAULT_HASH_INTERVAL,
    ): Boolean =
        runOnEmulationThread(true) {
            check(!isLinkActive) { "Link and controller netplay are mutually exclusive" }
            if (saveRam != null) {
                if (ownSaveRam == null) ownSaveRam = LibretroDroid.serializeSRAM()
                LibretroDroid.unserializeSRAM(saveRam)
            }
            LibretroDroid.startNetplay(localPort, players, inputDelay, hashInterval, rollback)
            val loaded = LibretroDroid.unserializeState(state)
            if (!loaded) LibretroDroid.stopNetplay()
            loaded
        }

    fun stopNetplay() = runOnEmulationThread(true) {
        LibretroDroid.stopNetplay()
        ownSaveRam?.let { LibretroDroid.unserializeSRAM(it) }
        ownSaveRam = null
    }

    @Volatile
    private var redrawOnly = false

    fun redraw() {
        redrawOnly = true
        requestRender()
    }

    fun pushNetplayInput(port: Int, frame: Int, buttons: Int) = LibretroDroid.setNetplayInput(port, frame, buttons)

    /** Receives the game while it streams: RGBA rows, top row first, on the GL thread. [pixels] is only valid during the call. */
    fun interface StreamSink {
        fun onFrame(pixels: ByteBuffer, width: Int, height: Int)
    }

    @Volatile private var streamSink: StreamSink? = null
    private var streamBuffer: ByteBuffer? = null
    private var streamWidth = 0
    private var streamHeight = 0

    /**
     * Streams the game at [width] x [height] (letterboxed) until [stopStream]: every frame to [sink], the sound
     * through [readStreamAudio]. The screen keeps playing; with [playHere] false it goes silent, so the sound is heard
     * only where it streams to. Same contract as the iOS player's startStream.
     */
    fun startStream(width: Int, height: Int, sink: StreamSink, playHere: Boolean = true) = queueEvent {
        stopStreamOnRenderThread()
        streamWidth = width
        streamHeight = height
        streamBuffer = ByteBuffer.allocateDirect(width * height * 4)
        streamSink = sink
        LibretroDroid.setStreamAudio(true)
        LibretroDroid.setStreamAudioOnly(!playHere)
    }

    /**
     * Only the sound, through [readStreamAudio], until [stopStreamAudio]: for a stream that takes its picture from
     * this view some other way (drawn into a virtual display). [playHere] as in [startStream].
     */
    fun startStreamAudio(playHere: Boolean = true) {
        LibretroDroid.setStreamAudio(true)
        LibretroDroid.setStreamAudioOnly(!playHere)
    }

    /** Ends [startStreamAudio]; this device plays the sound again. */
    fun stopStreamAudio() = LibretroDroid.setStreamAudio(false)

    fun stopStream() = queueEvent { stopStreamOnRenderThread() }

    /**
     * The streamed sound, exactly as played: 48 kHz interleaved 16-bit stereo into the direct [buffer]. Returns the
     * frames written, up to [frames]; fewer when the game has not played that much yet. Any thread.
     */
    fun readStreamAudio(buffer: ByteBuffer, frames: Int): Int = LibretroDroid.readStreamAudio(buffer, frames)

    private fun stopStreamOnRenderThread() {
        streamSink = null
        streamBuffer = null
        LibretroDroid.setStreamAudio(false)
        LibretroDroid.streamStop()
    }

    private fun streamFrame() {
        val sink = streamSink ?: return
        val buffer = streamBuffer ?: return
        buffer.clear()
        if (LibretroDroid.streamFrame(streamWidth, streamHeight, buffer)) sink.onFrame(buffer, streamWidth, streamHeight)
    }

    val netplayFrame: Int get() = LibretroDroid.netplayFrame()

    val netplayStalls: Int get() = LibretroDroid.netplayStalls()

    @Suppress("unused")
    private fun onNetplayOutgoing(type: Int, frame: Int, value: Long) {
        val listener = netplayListener ?: return
        when (type) {
            NETPLAY_LOCAL_INPUT -> listener.onLocalInput(frame, value.toInt())
            NETPLAY_STATE_HASH -> listener.onStateHash(frame, value)
            2 -> listener.onPerformance(frame, value)
            in 100..227 -> listener.onStateDiagnostic(frame, type - 100, value)
        }
    }

    fun getControllers(): Array<Array<Controller>> {
        return LibretroDroid.getControllers()
    }

    fun setControllerType(port: Int, type: Int) {
        LibretroDroid.setControllerType(port, type)
    }

    fun getVariables(): Array<Variable> {
        return LibretroDroid.getVariables()
    }

    fun updateVariables(vararg variables: Variable) {
        variables.forEach {
            LibretroDroid.updateVariable(it)
        }
    }

    fun getAvailableDisks(useEmulationThread: Boolean = true): Int {
        return runOnEmulationThread(useEmulationThread) { LibretroDroid.availableDisks() }
    }

    fun getCurrentDisk(useEmulationThread: Boolean = true): Int {
        return runOnEmulationThread(useEmulationThread) { LibretroDroid.currentDisk() }
    }

    fun changeDisk(index: Int, useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) { LibretroDroid.changeDisk(index) }
    }

    private fun getGLESVersion(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000) {
            3
        } else {
            2
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_DOWN, mappedKey, port)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_UP, mappedKey, port)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val port = (event?.device?.controllerNumber ?: 0) - 1
        if (port >= 0) {
            when (event?.source) {
                InputDevice.SOURCE_JOYSTICK -> {
                    sendMotionEvent(
                        MOTION_SOURCE_DPAD,
                        event.getAxisValue(MotionEvent.AXIS_HAT_X),
                        event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_LEFT,
                        event.getAxisValue(MotionEvent.AXIS_X),
                        event.getAxisValue(MotionEvent.AXIS_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_RIGHT,
                        event.getAxisValue(MotionEvent.AXIS_Z),
                        event.getAxisValue(MotionEvent.AXIS_RZ),
                        port
                    )
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private inner class RenderLifecycleObserver : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        private fun resume() = catchExceptions {
            LibretroDroid.resume()
            onResume()
            isEmulationReady = true
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        private fun pause() = catchExceptions {
            if (linkSession != null) stopLink()
            isEmulationReady = false
            onPause()
            LibretroDroid.pause()
        }
    }

    inner class Renderer : GLSurfaceView.Renderer {
        override fun onDrawFrame(gl: GL10) = catchExceptions {
            if (isEmulationReady && redrawOnly) {
                redrawOnly = false
                LibretroDroid.redraw()
            } else if (isEmulationReady) {
                linkSession?.let {
                    it.pump()
                    it.endReason?.let { reason ->
                        linkSession = null
                        onLinkEnded?.invoke(reason)
                    }
                }
                LibretroDroid.step(this@GLRetroView)
                streamFrame()
                LibretroDroid.achievementsEvents()?.let { achievementEventsSubject.tryEmit(it.decodeToString()) }
                lifecycle?.coroutineScope?.launch {
                    retroGLEventsSubject.emit(GLRetroEvents.FrameRendered)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) = catchExceptions {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            LibretroDroid.onSurfaceChanged(width, height)
        }

        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = catchExceptions {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            initializeCore()
            lifecycle?.coroutineScope?.launch {
                retroGLEventsSubject.emit(GLRetroEvents.SurfaceCreated)
            }
        }
    }

    private fun initializeCore() = catchExceptions {
        if (isGameLoaded) return@catchExceptions
        when {
            data.gameFilePath != null -> loadGameFromPath(data.gameFilePath!!)
            data.gameFileBytes != null -> loadGameFromBytes(data.gameFileBytes!!)
            data.gameVirtualFiles.isNotEmpty() -> loadGameFromVirtualFiles(data.gameVirtualFiles)
        }
        data.saveRAMState?.let {
            LibretroDroid.unserializeSRAM(data.saveRAMState)
            data.saveRAMState = null
        }
        LibretroDroid.onSurfaceCreated()
        isGameLoaded = true

        KtUtils.runOnUIThread {
            lifecycle?.addObserver(RenderLifecycleObserver())
        }
    }

    private fun loadGameFromVirtualFiles(virtualFiles: List<VirtualFile>) {
        val detachedVirtualFiles = virtualFiles
            .map { DetachedVirtualFile(it.virtualPath, it.fileDescriptor.detachFd()) }
        LibretroDroid.loadGameFromVirtualFiles(detachedVirtualFiles)
    }

    private fun loadGameFromBytes(gameFileBytes: ByteArray) {
        LibretroDroid.loadGameFromBytes(gameFileBytes)
    }

    private fun loadGameFromPath(gameFilePath: String) {
        LibretroDroid.loadGameFromPath(gameFilePath)
    }

    private fun catchExceptions(block: () -> Unit) {
        try {
            if (isAborted) return
            block()
        } catch (e: RetroException) {
            GlobalScope.launch {
                retroGLIssuesErrors.emit(e.errorCode)
            }
            isAborted = true
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error in GLRetroView", e)
            GlobalScope.launch {
                retroGLIssuesErrors.emit(LibretroDroid.ERROR_GENERIC)
            }
        }
    }

    private fun <T> runOnEmulationThread(useEmulationThread: Boolean, block: () -> T): T {
        if (!useEmulationThread || Thread.currentThread().name.startsWith("GLThread")) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        queueEvent {
            try { result = runCatching(block) } finally { latch.countDown() }
        }

        latch.awaitUninterruptibly()
        return result!!.getOrThrow()
    }

    private fun buildShader(config: ShaderConfig): GLRetroShader {
        return when (config) {
            is ShaderConfig.Default -> GLRetroShader(LibretroDroid.SHADER_DEFAULT)
            is ShaderConfig.CRT -> GLRetroShader(LibretroDroid.SHADER_CRT)
            is ShaderConfig.LCD -> GLRetroShader(LibretroDroid.SHADER_LCD)
            is ShaderConfig.Sharp -> GLRetroShader(LibretroDroid.SHADER_SHARP)
            is ShaderConfig.Retro -> GLRetroShader(
                LibretroDroid.SHADER_RETRO,
                buildParams(
                    "SMOOTH" to if (config.smooth) "1" else "0",
                    "GRID" to toParam(config.grid),
                    "SUBPIXEL" to toParam(config.subpixel),
                    "SCANLINES" to toParam(config.scanlines),
                    "BRIGHTNESS" to toParam(config.brightness),
                )
            )
            is ShaderConfig.CUT -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_VALUE to toParam(config.edgeMinValue),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_CONTRAST to toParam(config.edgeMinContrast),
                )
            )

            is ShaderConfig.CUT2 -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT2,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING to toParam(config.softEdgesSharpening),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING_AMOUNT to toParam(config.softEdgesSharpeningAmount),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_HARD_EDGES_SEARCH_MAX_ERROR to toParam(config.hardEdgesSearchMaxError),
                )
            )

            is ShaderConfig.CUT3 -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT3,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING to toParam(config.softEdgesSharpening),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING_AMOUNT to toParam(config.softEdgesSharpeningAmount),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_ERROR to toParam(config.hardEdgesSearchMaxError),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_DISTANCE to toParam(config.hardEdgesSearchMaxDistance),
                )
            )
        }
    }

    private fun toParam(param: Float): String {
        return param.toString()
    }

    private fun toParam(param: Boolean): String {
        return if (param) {
            "1"
        } else {
            "0"
        }
    }

    private fun toParam(param: Int): String {
        return param.toString()
    }

    private fun buildParams(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs
            .filter { (key, value) -> value != null }
            .associate { (key, value) -> key to value!! }
    }

    private fun sendRumbleEvent(port: Int, strengthWeak: Float, strengthStrong: Float) {
        lifecycle?.coroutineScope?.launch {
            rumbleEventsSubject.emit(RumbleEvent(port, strengthWeak, strengthStrong))
        }
    }

    private fun refreshAspectRatio() {
        runOnEmulationThread(true) {
            LibretroDroid.refreshAspectRatio()
        }
    }

    sealed class GLRetroEvents {
        object FrameRendered : GLRetroEvents()
        object SurfaceCreated : GLRetroEvents()
    }

    companion object {
        private val TAG_LOG = GLRetroView::class.java.simpleName

        const val MOTION_SOURCE_DPAD = LibretroDroid.MOTION_SOURCE_DPAD
        const val MOTION_SOURCE_ANALOG_LEFT = LibretroDroid.MOTION_SOURCE_ANALOG_LEFT
        const val MOTION_SOURCE_ANALOG_RIGHT = LibretroDroid.MOTION_SOURCE_ANALOG_RIGHT
        const val MOTION_SOURCE_POINTER = LibretroDroid.MOTION_SOURCE_POINTER

        const val ERROR_LOAD_LIBRARY = LibretroDroid.ERROR_LOAD_LIBRARY
        const val ERROR_LOAD_GAME = LibretroDroid.ERROR_LOAD_GAME
        const val ERROR_GL_NOT_COMPATIBLE = LibretroDroid.ERROR_GL_NOT_COMPATIBLE
        const val ERROR_SERIALIZATION = LibretroDroid.ERROR_SERIALIZATION
        const val ERROR_CHEAT = LibretroDroid.ERROR_CHEAT
        const val ERROR_GENERIC = LibretroDroid.ERROR_GENERIC

        private val TOUCH_EVENT_OUTSIDE = PointF(-10f, 10f)

        // Full PCSX serialization is several MiB and runs on the emulation
        // thread. Keep diagnostics sparse so they do not become frame hitches.
        private const val DEFAULT_HASH_INTERVAL = 600
        private const val NETPLAY_LOCAL_INPUT = 0
        private const val NETPLAY_STATE_HASH = 1
    }
}
