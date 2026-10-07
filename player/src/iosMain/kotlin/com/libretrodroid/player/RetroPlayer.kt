package com.libretrodroid.player

import com.libretrodroid.netplay.NetpacketSession
import com.libretrodroid.netplay.TcpConnection
import com.libretrodroid.engine.native.RE_NETPLAY_LOCAL_INPUT
import com.libretrodroid.engine.native.RE_NETPLAY_STATE_HASH
import com.libretrodroid.engine.native.RE_SHADER_SHARP
import com.libretrodroid.engine.native.re_attach_layer
import com.libretrodroid.engine.native.re_capture
import com.libretrodroid.engine.native.re_observe_video
import com.libretrodroid.engine.native.re_disk_count
import com.libretrodroid.engine.native.re_disk_current
import com.libretrodroid.engine.native.re_disk_set
import com.libretrodroid.engine.native.re_redraw
import com.libretrodroid.engine.native.re_set_aspect_ratio_override
import com.libretrodroid.engine.native.re_config
import com.libretrodroid.engine.native.re_core_version
import com.libretrodroid.engine.native.re_create
import com.libretrodroid.engine.native.re_destroy
import com.libretrodroid.engine.native.re_detach_layer
import com.libretrodroid.engine.native.re_frame
import com.libretrodroid.engine.native.re_jit_available
import com.libretrodroid.engine.native.re_set_variable
import com.libretrodroid.engine.native.re_link_keep
import com.libretrodroid.engine.native.re_ereader_supported
import com.libretrodroid.engine.native.re_ereader_scan
import com.libretrodroid.engine.native.re_link_load_save
import com.libretrodroid.engine.native.re_link_max_players
import com.libretrodroid.engine.native.re_link_set_grid
import com.libretrodroid.engine.native.re_link_set_local
import com.libretrodroid.engine.native.re_link_set_players
import com.libretrodroid.engine.native.re_free
import com.libretrodroid.engine.native.re_last_error
import com.libretrodroid.engine.native.re_load_game
import com.libretrodroid.engine.native.re_netplay_set_input
import com.libretrodroid.engine.native.re_netplay_start
import com.libretrodroid.engine.native.re_netplay_stop
import com.libretrodroid.engine.native.re_pause
import com.libretrodroid.engine.native.re_reset
import com.libretrodroid.engine.native.re_resume
import com.libretrodroid.engine.native.re_serialize
import com.libretrodroid.engine.native.re_serialize_sram
import com.libretrodroid.engine.native.re_set_buttons
import com.libretrodroid.engine.native.re_set_audio_tap
import com.libretrodroid.engine.native.re_set_audio_tap_only
import com.libretrodroid.engine.native.re_set_motion
import com.libretrodroid.engine.native.re_set_multitap
import com.libretrodroid.engine.native.re_stream_frame
import com.libretrodroid.engine.native.re_stream_stop
import com.libretrodroid.engine.native.re_stream_surface
import com.libretrodroid.engine.native.re_surface_changed
import com.libretrodroid.engine.native.re_surface_created
import com.libretrodroid.engine.native.re_unserialize
import com.libretrodroid.engine.native.re_unserialize_sram
import com.libretrodroid.engine.native.re_set_speed
import com.libretrodroid.engine.native.re_effective_speed
import com.libretrodroid.engine.native.re_set_rewind
import com.libretrodroid.engine.native.re_set_audio_volume
import com.libretrodroid.engine.native.re_set_frame_skip
import com.libretrodroid.engine.native.re_set_run_ahead
import com.libretrodroid.engine.native.re_set_vsync
import com.libretrodroid.engine.native.re_set_rewinding
import com.libretrodroid.engine.native.re_rewind_seconds
import com.libretrodroid.engine.native.re_sensors_requested
import com.libretrodroid.engine.native.re_set_sensor
import com.libretrodroid.engine.native.re_set_rumble_enabled
import com.libretrodroid.engine.native.re_poll_rumble
import com.libretrodroid.engine.native.re_cheat_reset
import com.libretrodroid.engine.native.re_cheat_set
import com.libretrodroid.engine.native.re_set_shader
import com.libretrodroid.engine.native.re_variables_json
import com.libretrodroid.engine.native.re_ra_enable
import com.libretrodroid.engine.native.re_ra_disable
import com.libretrodroid.engine.native.re_ra_login
import com.libretrodroid.engine.native.re_ra_logout
import com.libretrodroid.engine.native.re_ra_load_game
import com.libretrodroid.engine.native.re_ra_set_hardcore
import com.libretrodroid.engine.native.re_ra_hardcore
import com.libretrodroid.engine.native.re_ra_http_response
import com.libretrodroid.engine.native.re_ra_idle
import com.libretrodroid.engine.native.re_ra_list
import com.libretrodroid.engine.native.re_ra_can_pause
import com.libretrodroid.engine.native.re_ra_events
import com.libretrodroid.engine.native.RE_SHADER_RETRO
import com.libretrodroid.netplay.HostStart
import com.libretrodroid.netplay.NetplayEmulator
import com.libretrodroid.netplay.NetplayListener
import kotlinx.atomicfu.AtomicIntArray
import kotlinx.atomicfu.AtomicLongArray
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.toCStringArray
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithBytesNoCopy
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSLocale
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.languageCode
import platform.Foundation.runMode
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAFrameRateRangeMake
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.size_tVar
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ObjCAction

data class RetroGame(
    val corePath: String,
    val gamePath: String,
    val systemDir: String,
    val savesDir: String,
    val sram: ByteArray? = null,
    val recompilerOption: String? = null,

    val netplayMinDelay: Int = 0,

    /** Deprecated in favour of [aspectRatio]; true is [AspectRatio.FILL]. */
    val widescreen: Boolean = false,
    /** [AspectRatio.CORE], [AspectRatio.FILL] or a fixed width/height ratio such as 16/9. */
    val aspectRatio: Float = AspectRatio.CORE,
    /** Core options set before the core starts: those marked "(Restart)" only take effect this way. */
    val options: Map<String, String> = emptyMap(),
    /** The picture's filter; [RetroShader.Sharp] by default. */
    val shader: RetroShader = RetroShader.Sharp,
)

/** The picture's filter. [Retro] combines its effects in one pass, each from 0 to 1. */
sealed interface RetroShader {
    data object Default : RetroShader
    data object Sharp : RetroShader
    data object Crt : RetroShader
    data object Lcd : RetroShader
    data object Upscale : RetroShader
    data class Retro(
        val smooth: Boolean = false,
        val grid: Float = 0f,
        val subpixel: Float = 0f,
        val scanlines: Float = 0f,
        val brightness: Float = 1f,
    ) : RetroShader
}

/** The latest rumble of a controller port, both motors 0..1. */
fun interface RetroRumble {
    fun rumble(port: Int, weak: Float, strong: Float)
}

/** Events of RetroAchievements as a JSON array; delivered on the render thread after the frame that raised them. */
fun interface RetroAchievementEvents {
    fun events(json: String)
}

/** Picture shape. Nothing is cropped: the core's and fixed ratios are letterboxed inside the view. */
object AspectRatio {
    /** The core's own ratio. */
    const val CORE = 0f

    /** Stretches to the view, and to the frame while streaming. */
    const val FILL = -1f
}

class RetroFrame(val pixels: NSData, val width: Int, val height: Int)

/**
 * Receives the game while it streams. [video]: RGBA rows, top row first, on the render thread.
 * [audio]: 48 kHz interleaved 16-bit stereo, on the audio thread. The data is only valid during the call.
 */
interface RetroStreamSink {
    /**
     * The frame just rendered as a BGRA `CVPixelBuffer` on an IOSurface, which a hardware encoder takes as it is:
     * nothing was copied or converted. On the render thread; retain it to keep it past the call. A device that
     * cannot render this way calls the other [video] instead.
     */
    fun video(pixelBuffer: COpaquePointer)

    fun video(pixels: NSData, width: Int, height: Int)
    fun audio(samples: NSData, frames: Int)
}

class RetroPlayer(
    private val game: RetroGame,
    override val rollback: Boolean,
) : NetplayEmulator {

    val view: UIView = GameView()

    @Volatile var netplayListener: NetplayListener? = null

    var onReady: (() -> Unit)? = null

    var onError: ((String) -> Unit)? = null

    var coreVersion: String = ""
        private set

    private val gamepads = GamepadInput()
    private val touchButtons = atomic(0)
    private val remoteButtons = AtomicIntArray(PORTS)
    private val remoteAxes = AtomicLongArray(PORTS)
    private val appliedAxes = LongArray(PORTS)
    @Volatile private var localPort = 0
    @Volatile private var sink: RetroStreamSink? = null
    private var streamSize = 0 to 0
    private var sinkRef: StableRef<RetroPlayer>? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var aspectRatio = if (game.widescreen) AspectRatio.FILL else game.aspectRatio
    private var ready = false
    private var sramOnExit: ByteArray? = null
    private val stopped = dispatch_semaphore_create(0)

    private val tasksLock = SynchronizedObject()
    private val tasks = ArrayList<() -> Unit>()

    /** Bindings for controllers and keyboards; null keeps the classic layout. Called on the main thread. */
    var inputMapper: RetroInputMapper?
        get() = gamepads.mapper
        set(value) { gamepads.mapper = value }

    fun setTouchButtons(mask: Int) {
        touchButtons.value = mask
    }

    /** Port that this device's touch pad and controllers drive; -1 sends them nowhere. */
    fun setLocalPort(port: Int) {
        localPort = port.takeIf { it in 0 until PORTS } ?: -1
    }

    /**
     * Input of a player on another device. Axes are signed 16-bit, +y down: left stick, right stick.
     * Applied at the next frame, like local input.
     */
    fun setRemoteInput(port: Int, buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        if (port !in 0 until PORTS) return
        remoteButtons[port].value = buttons and 0xffff
        remoteAxes[port].value = pack(lx, ly, rx, ry)
    }

    /** The running game is the e-Reader and can scan cards. */
    val ereaderSupported: Boolean get() = onRenderThread { re_ereader_supported() }

    /** Puts a card's dot code strip (.raw) in front of the e-Reader; false when the game has no scanner. */
    fun ereaderScan(card: ByteArray): Boolean = onRenderThread { card.isNotEmpty() && card.load { data, size -> re_ereader_scan(data, size) } }

    /** Up to four players on cores with a multitap; returns whether it is active. */
    fun setMultitap(enabled: Boolean): Boolean = onRenderThread { re_set_multitap(enabled) }

    /** Streams at [width] x [height] (letterboxed) until [stopStream]. The screen keeps playing too. */
    fun startStream(width: Int, height: Int, sink: RetroStreamSink) = startStream(width, height, sink, playHere = true)

    /**
     * [startStream]; with [playHere] false this device goes silent, so the sound is heard only where it streams to.
     */
    fun startStream(width: Int, height: Int, sink: RetroStreamSink, playHere: Boolean) = onRenderThread {
        stopStreamOnRenderThread()
        streamSize = width to height
        this.sink = sink
        val ref = StableRef.create(this).also { sinkRef = it }
        re_set_audio_tap(streamAudio, ref.asCPointer())
        re_set_audio_tap_only(!playHere)
    }

    fun stopStream() = onRenderThread { stopStreamOnRenderThread() }

    private fun stopStreamOnRenderThread() {
        re_set_audio_tap(null, null)
        sinkRef?.dispose()
        sinkRef = null
        sink = null
        re_stream_stop()
    }

    @Volatile private var linkSession: NetpacketSession? = null
    val isLinkActive: Boolean get() = linkSession != null

    /** Connection must already have completed compatibility negotiation. */
    fun startLink(connection: TcpConnection, localId: Int): Boolean = onRenderThread {
        check(linkSession == null && running && !paused)
        stop()
        val session = NetpacketSession(connection, IosNetpacketCore(), localId)
        if (session.start()) { linkSession = session; true } else false
    }

    fun stopLink() = onRenderThread { stopLinkOnRenderThread() }

    /** Called once from the render thread with the reason when a running link session ends by itself. */
    var onLinkEnded: ((String) -> Unit)? = null

    /** Changes a core option while running; the core applies it on its next frame. */
    fun setVariable(key: String, value: String) = onRenderThread { re_set_variable(key, value) }

    private fun stopLinkOnRenderThread() {
        linkSession?.stopOnEmulationThread()
        linkSession = null
    }

    fun setPaused(paused: Boolean) = onRenderThread {
        if (paused == this.paused) return@onRenderThread
        if (paused) stopLinkOnRenderThread()
        this.paused = paused
        if (paused) re_pause() else re_resume()
    }

    fun start() {
        check(!running) { "already started" }
        running = true
        configureAudioSession()
        gamepads.start()
        val layer = interpretCPointer<kotlinx.cinterop.CPointed>(view.layer.objcPtr())
        NSThread(block = { renderThread(layer) }).apply {
            setName("retro-render")
            qualityOfService = NSQualityOfServiceUserInteractive
            start()
        }
    }

    fun finish(): ByteArray? {
        if (!running) return sramOnExit
        running = false
        dispatch_semaphore_wait(stopped, DISPATCH_TIME_FOREVER)
        gamepads.stop()
        return sramOnExit
    }

    override fun reset() = onRenderThread { check(!isLinkActive); re_reset() }

    /**
     * Emulation speed: 1 normal, 0.1..0.99 slow motion, above 1 fast-forward up to 100x (as many frames as fit in a
     * display refresh), 0 as fast as the device can. A link cable session plays only at 1. Cheap: no render-thread hop.
     */
    fun setSpeed(speed: Float) {
        if (isLinkActive && speed != 1f) return
        this.speed = speed
        re_set_speed(speed)
    }

    @Volatile private var speed = 1f
    @Volatile private var rewinding = false

    /**
     * Reads the pads as late in each refresh as the frame still reaches the screen on time, instead of at its
     * start: up to about 10 ms less between a press and its picture on light systems. On by default.
     */
    @Volatile var lateInput = true

    /** Frames the core ran per displayed frame lately (what fast-forward reached); negative while rewinding. */
    val effectiveSpeed: Float get() = re_effective_speed()

    /** Run-ahead frames (0..6): the picture shows that many frames ahead, removing the games' own input lag. */
    fun setRunAhead(frames: Int) = re_set_run_ahead(frames.coerceAtLeast(0).toUInt())

    /** How loud the game plays on this device, 0 (silent) to 1; a stream to another screen keeps its full volume. */
    fun setAudioVolume(volume: Float) = re_set_audio_volume(volume.coerceIn(0f, 1f))

    /** False paces frames by the clock instead of the display's refresh, even when both rates match. */
    fun setVSync(enabled: Boolean) = re_set_vsync(enabled)

    /** Whether a late frame is made up by running two and drawing the second (only when the clock paces frames). */
    fun setFrameSkip(enabled: Boolean) = re_set_frame_skip(enabled)

    /** Keeps the last [budgetBytes] of play to rewind through; 0 turns it off and frees it. */
    fun setRewind(budgetBytes: Long) = re_set_rewind(budgetBytes.coerceAtLeast(0).toULong())

    /** While true each displayed frame steps back through the recorded play instead of advancing. */
    fun setRewinding(rewinding: Boolean) {
        this.rewinding = rewinding
        re_set_rewinding(rewinding)
    }

    val rewindSeconds: Float get() = re_rewind_seconds()

    /** Sensors the core switched on: 1 accelerometer, 2 gyroscope, 4 light. Cheap: poll it. */
    val sensorsRequested: Int get() = re_sensors_requested().toInt()

    /** Latest reading of libretro sensor [id]: accelerometer 0-2 (m/s²), gyroscope 3-5 (rad/s), light 6 (lux). */
    fun setSensor(id: Int, value: Float) = re_set_sensor(id.toUInt(), value)

    @Volatile var rumble: RetroRumble? = null
        set(value) {
            field = value
            re_set_rumble_enabled(value != null)
        }

    fun setCheats(codes: List<String>) = onRenderThread {
        re_cheat_reset()
        codes.forEachIndexed { index, code -> re_cheat_set(index.toUInt(), true, code) }
    }

    fun setShader(shader: RetroShader) = onRenderThread {
        applyShader(shader)
        if (paused) re_redraw()
    }

    /** Core options as JSON: [{key, value, description}], description being "Label; value1|value2|...". */
    fun coreOptionsJson(): String = onRenderThread { takeString { re_variables_json() } ?: "[]" }

    @Volatile var achievementEvents: RetroAchievementEvents? = null

    fun achievementsEnable(userAgent: String, hardcore: Boolean, unofficial: Boolean) =
        onRenderThread { re_ra_enable(userAgent, hardcore, unofficial) }

    fun achievementsDisable() = onRenderThread { re_ra_disable() }

    fun achievementsLogin(username: String, secret: String, isToken: Boolean) =
        onRenderThread { re_ra_login(username, secret, isToken) }

    fun achievementsLogout() = onRenderThread { re_ra_logout() }

    /** After the game is running: identifies [path] for RetroAchievements console [consoleId] and loads its set. */
    fun achievementsLoadGame(path: String, consoleId: Int) = onRenderThread { re_ra_load_game(path, consoleId.toUInt()) }

    fun achievementsSetHardcore(enabled: Boolean) = onRenderThread { re_ra_set_hardcore(enabled) }

    val achievementsHardcore: Boolean get() = re_ra_hardcore()

    /** Answers an "http" event; [status] <= 0 when the request never reached the server. Any thread. */
    fun achievementsHttpResponse(id: Long, status: Int, body: ByteArray) {
        if (body.isEmpty()) re_ra_http_response(id, status, null, 0u)
        else body.usePinned { re_ra_http_response(id, status, it.addressOf(0), body.size.convert()) }
    }

    fun achievementsListJson(): String = takeString { re_ra_list() } ?: "[]"

    /** 0 when the game may pause now; otherwise frames hardcore asks to play first. */
    fun achievementsPauseWait(): Int = memScoped {
        val remaining = alloc<UIntVar>()
        if (re_ra_can_pause(remaining.ptr)) 0 else remaining.value.toInt().coerceAtLeast(1)
    }

    /** Keeps the network queue moving while the game is paused; returns the pending events (JSON array) or null. Any thread. */
    fun achievementsIdle(): String? {
        re_ra_idle()
        return takeString { re_ra_events() }
    }

    fun setWidescreen(enabled: Boolean) = setAspectRatio(if (enabled) AspectRatio.FILL else AspectRatio.CORE)

    /** [AspectRatio.CORE], [AspectRatio.FILL] or a fixed width/height ratio such as 16/9. */
    fun setAspectRatio(ratio: Float) = onRenderThread {
        aspectRatio = ratio
        applyAspectRatio()

        if (paused) re_redraw()
    }

    fun disks(): Pair<Int, Int> = onRenderThread { re_disk_count() to re_disk_current() }

    fun setDisk(index: Int) = onRenderThread { re_disk_set(index.toUInt()) }

    /** Immutable observation, independent of the EGL surface and render queue. */
    fun observeVideo(): ByteArray? = takeBytes { re_observe_video(it) }

    fun capture(): RetroFrame? = onRenderThread {
        memScoped {
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val data = re_capture(width.ptr, height.ptr) ?: return@memScoped null
            val frame = RetroFrame(NSData.dataWithBytes(data, (width.value * height.value * 4).convert()), width.value, height.value)
            re_free(data)
            frame
        }
    }

    fun serializeSram(): ByteArray? = onRenderThread { takeBytes { re_serialize_sram(it) } }

    fun serializeState(): ByteArray? = onRenderThread { check(!isLinkActive); takeBytes { re_serialize(it) } }

    fun unserializeState(state: ByteArray): Boolean = onRenderThread { if (isLinkActive) return@onRenderThread false; state.load { data, size -> re_unserialize(data, size) } }

    override val minInputDelay: Int get() = game.netplayMinDelay

    private var ownSaveRam: ByteArray? = null

    override fun startAsHost(players: Int, inputDelay: Int): HostStart = onRenderThread {

        check(!isLinkActive)
        re_netplay_start(0, players, inputDelay, HASH_INTERVAL, rollback)
        val state = takeBytes { re_serialize(it) } ?: ByteArray(0)
        // Linked consoles: loading the state rebuilds the cable, exactly as on every joining device.
        if (re_link_max_players() > 1) state.load { data, size -> re_unserialize(data, size) }
        HostStart(
            state = state,
            saveRam = takeBytes { re_serialize_sram(it) }?.takeIf { it.isNotEmpty() },
        )
    }

    /** Consoles the core links inside itself (retrolink): 1 for an ordinary core. */
    override val linkMaxPlayers: Int by lazy { onRenderThread { re_link_max_players() } }

    private var linkLocal = 0

    override fun linkConsoles(players: Int, saves: Map<Int, ByteArray>, rebuild: Boolean) = onRenderThread {
        if (rebuild) re_link_set_players(1)
        re_link_set_players(players)
        saves.forEach { (port, save) -> save.load { data, size -> re_link_load_save(port, data, size) } }
        Unit
    }

    override fun linkConsoleSave(port: Int): ByteArray? = onRenderThread {
        re_link_set_local(port)
        takeBytes { re_serialize_sram(it) }.also { re_link_set_local(linkLocal) }
    }

    override fun setLinkLocal(port: Int) = onRenderThread {
        linkLocal = port
        re_link_set_local(port)
    }

    override fun linkLocalSave(): ByteArray? = onRenderThread { takeBytes { re_serialize_sram(it) } }

    override fun linkKeepLocal() = onRenderThread {
        re_link_keep(linkLocal)
        linkLocal = 0
    }

    /**
     * Players sharing this device on a retrolink core: [players] linked consoles, controller port p on console p,
     * shown side by side (a 2x2 grid for 3 or 4). 1 goes back to a single console.
     */
    fun setLocalLinkPlayers(players: Int): Boolean = onRenderThread {
        val linked = re_link_set_players(players)
        re_link_set_grid(linked && players > 1)
        if (linked) localPlayers = players
        linked
    }

    /** Players sharing this device: each controller plays its own port, the touch pad port 0. */
    @Volatile private var localPlayers = 1

    override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?): Boolean = onRenderThread {

        check(!isLinkActive)
        if (saveRam != null) {
            if (ownSaveRam == null) ownSaveRam = takeBytes { re_serialize_sram(it) }
            saveRam.load { data, size -> re_unserialize_sram(data, size) }
        }

        re_netplay_start(port, players, inputDelay, HASH_INTERVAL, rollback)
        val loaded = state.load { data, size -> re_unserialize(data, size) }
        if (!loaded) re_netplay_stop()
        loaded
    }

    override fun stop() = onRenderThread {
        re_netplay_stop()
        ownSaveRam?.let { own -> own.load { data, size -> re_unserialize_sram(data, size) } }
        ownSaveRam = null
    }

    override fun pushInput(port: Int, frame: Int, buttons: Int) =
        re_netplay_set_input(port, frame.toUInt(), buttons.toUShort())

    val jitAvailable: Boolean = re_jit_available()

    override val canRecompile: Boolean get() = game.recompilerOption != null && jitAvailable

    override fun setRecompiler(enabled: Boolean) {
        val key = game.recompilerOption ?: return
        onRenderThread { re_set_variable(key, if (enabled && jitAvailable) "enabled" else "disabled") }
    }

    /** The link ticking the game, replaced when the picture moves to another screen. Render thread only. */
    private var link: CADisplayLink? = null

    /**
     * Ticks with [screen]'s refresh, or the phone's when null. A TV (AirPlay or cable) refreshes on a clock of its
     * own: frames paced by the phone's reached it at a drifting moment of its refresh, each waiting up to a whole
     * one, and now and then one was shown twice or not at all.
     */
    private fun follow(ticker: Ticker, screen: UIScreen?) {
        link?.invalidate()
        val selector = NSSelectorFromString("tick:")
        link = (screen?.displayLinkWithTarget(ticker, selector) ?: CADisplayLink.displayLinkWithTarget(ticker, selector)).apply {
            preferredFrameRateRange = CAFrameRateRangeMake(60f, 60f, 60f)
            addToRunLoop(NSRunLoop.currentRunLoop, NSRunLoopCommonModes)
        }
    }

    private fun renderThread(layer: kotlinx.cinterop.CPointer<kotlinx.cinterop.CPointed>?) {
        val self = StableRef.create(this)
        lateinit var ticker: Ticker
        ticker = Ticker { tick ->
            (view as GameView).takeScreen()?.let { follow(ticker, it) }
            frame(self.asCPointer(), tick)
        }
        try {
            if (!open(layer)) {
                fail(re_last_error()?.toKString() ?: "failed to open the game")
                return
            }
            follow(ticker, (view as GameView).takeScreen())
            while (running) {
                NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.1))
            }
        } finally {
            link?.invalidate()
            link = null
            runPendingTasks()
            stopLinkOnRenderThread()
            stopStreamOnRenderThread()
            sramOnExit = takeBytes { re_serialize_sram(it) }
            re_destroy()
            re_detach_layer()
            self.dispose()
            dispatch_semaphore_signal(stopped)
        }
    }

    private fun open(layer: kotlinx.cinterop.CPointer<kotlinx.cinterop.CPointed>?): Boolean = memScoped {
        if (!re_attach_layer(layer)) return false
        val options = game.options.entries.toList()
        val config = alloc<re_config>().apply {
            core_path = game.corePath.cstr.ptr
            system_dir = game.systemDir.cstr.ptr
            saves_dir = game.savesDir.cstr.ptr
            language = (NSLocale.currentLocale.languageCode ?: "en").cstr.ptr
            refresh_rate = 60f
            shader = RE_SHADER_SHARP
            variable_keys = options.map { it.key }.toCStringArray(this@memScoped)
            variable_values = options.map { it.value }.toCStringArray(this@memScoped)
            variable_count = options.size
        }
        if (!re_create(config.ptr) || !re_load_game(game.gamePath)) return false
        applyShader(game.shader)
        game.sram?.load { data, size -> re_unserialize_sram(data, size) }
        if (!re_surface_created()) return false
        (view as GameView).pixelSize.let { (w, h) -> re_surface_changed(w, h) }
        applyAspectRatio()
        re_resume()
        val version = allocArray<ByteVar>(128)
        re_core_version(version, 128u)
        coreVersion = version.toKString()
        true
    }

    /** How long a frame takes from reading the pads to handing the picture over, lately; seconds. */
    private var frameCost = 0.0
    /** Kept free before the refresh on top of [frameCost]; grows with every frame that came out late. */
    private var frameMargin = LATCH_MARGIN

    /**
     * Waits inside the refresh interval so the pads are read as late as the frame still makes it to the screen
     * (RetroArch's automatic frame delay). A game that takes 3 ms to run was reading them 16 ms before its picture
     * showed; read 8 ms later, a press reaches the same picture it used to miss. Only at normal speed.
     */
    private fun waitForLateInput(tick: CADisplayLink) {
        if (!lateInput || speed != 1f || rewinding || linkSession != null || frameCost <= 0.0) return
        val spare = tick.targetTimestamp - CACurrentMediaTime() - frameCost * LATCH_COST_FACTOR - frameMargin
        if (spare > LATCH_MIN) usleep((minOf(spare, LATCH_MAX) * MICROS).toUInt())
    }

    /** After a frame: what it cost, and whether it was ready in time for the refresh it aimed at. */
    private fun measureFrame(tick: CADisplayLink, started: Double) {
        val now = CACurrentMediaTime()
        val cost = now - started
        // Up at once, down slowly: one heavy frame is enough to leave it room.
        frameCost = if (cost > frameCost) cost else frameCost * LATCH_DECAY + cost * (1 - LATCH_DECAY)
        frameMargin = if (now > tick.targetTimestamp - LATCH_PRESENT) minOf(frameMargin + LATCH_MARGIN_STEP, LATCH_MARGIN_MAX)
            else maxOf(frameMargin - LATCH_MARGIN_STEP / LATCH_RECOVER_FRAMES, LATCH_MARGIN)
    }

    private fun frame(self: COpaquePointer, tick: CADisplayLink) {
        runPendingTasks()
        (view as GameView).takeResize()?.let { (w, h) ->
            re_surface_changed(w, h)
            applyAspectRatio()
            if (paused) re_redraw()
        }
        if (paused) return
        waitForLateInput(tick)
        val started = CACurrentMediaTime()
        val local = gamepads.buttons or touchButtons.value
        val sharing = localPlayers > 1
        for (port in 0 until PORTS) {
            val here = when {
                sharing -> gamepads.buttons(port) or if (port == 0) touchButtons.value else 0
                port == localPort -> local
                else -> 0
            }
            val mask = remoteButtons[port].value or here
            re_set_buttons(port.toUInt(), mask.toUShort())
            val axes = remoteAxes[port].value
            if (axes != appliedAxes[port]) {
                appliedAxes[port] = axes
                re_set_motion(port.toUInt(), SOURCE_LEFT, axis(axes, 0), axis(axes, 1))
                re_set_motion(port.toUInt(), SOURCE_RIGHT, axis(axes, 2), axis(axes, 3))
            }
        }
        linkSession?.let {
            it.pump()
            it.endReason?.let { reason ->
                linkSession = null
                onLinkEnded?.invoke(reason)
            }
        }
        re_frame(outgoing, self)
        if (rumble != null) re_poll_rumble(rumbleCallback, self)
        achievementEvents?.let { sinkEvents -> takeString { re_ra_events() }?.let(sinkEvents::events) }
        if (sink != null) streamSize.let { (w, h) ->
            // FILL means the frame's shape here, not the phone's: the TV would get a narrow portrait picture.
            if (aspectRatio == AspectRatio.FILL) re_set_aspect_ratio_override(overrideFor(w, h))
            if (!re_stream_surface(w, h, streamSurface, self)) re_stream_frame(w, h, streamVideo, self)
            if (aspectRatio == AspectRatio.FILL) applyAspectRatio()
        }
        measureFrame(tick, started)
        if (!ready) {
            ready = true
            dispatch_async(dispatch_get_main_queue()) { onReady?.invoke() }
        }
    }

    private fun applyShader(shader: RetroShader) = when (shader) {
        RetroShader.Default -> re_set_shader(0, null)
        RetroShader.Crt -> re_set_shader(1, null)
        RetroShader.Lcd -> re_set_shader(2, null)
        RetroShader.Sharp -> re_set_shader(RE_SHADER_SHARP, null)
        RetroShader.Upscale -> re_set_shader(5, null)
        is RetroShader.Retro -> re_set_shader(
            RE_SHADER_RETRO,
            "SMOOTH=${if (shader.smooth) 1 else 0};GRID=${shader.grid};SUBPIXEL=${shader.subpixel};" +
                "SCANLINES=${shader.scanlines};BRIGHTNESS=${shader.brightness}",
        )
    }

    private fun applyAspectRatio() {
        val (w, h) = (view as GameView).pixelSize
        re_set_aspect_ratio_override(overrideFor(w, h))
    }

    private fun overrideFor(width: Int, height: Int): Float = when {
        aspectRatio > 0f -> aspectRatio
        aspectRatio == AspectRatio.FILL && height > 1 -> width.toFloat() / height
        else -> AspectRatio.CORE
    }

    private fun fail(message: String) {
        dispatch_async(dispatch_get_main_queue()) { onError?.invoke(message) }
    }

    private fun <T> onRenderThread(block: () -> T): T {
        if (NSThread.currentThread.name == "retro-render") return block()
        val done = dispatch_semaphore_create(0)
        var result: Result<T>? = null
        synchronized(tasksLock) {
            tasks += {
                result = runCatching(block)
                dispatch_semaphore_signal(done)
            }
        }
        if (!running) runPendingTasks()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        return result!!.getOrThrow()
    }

    private fun runPendingTasks() {
        val batch = synchronized(tasksLock) { tasks.toList().also { tasks.clear() } }
        batch.forEach { it() }
    }

    private fun configureAudioSession() {
        AVAudioSession.sharedInstance().apply {
            setCategory(AVAudioSessionCategoryPlayback, null)
            setPreferredIOBufferDuration(0.005, null)
            setActive(true, null)
        }
    }

    private companion object {

        const val HASH_INTERVAL = 600
        const val PORTS = 4
        const val SOURCE_LEFT = 1
        const val SOURCE_RIGHT = 2

        const val MICROS = 1_000_000
        /** A frame may take this much longer than lately and still be on time. */
        const val LATCH_COST_FACTOR = 1.5
        const val LATCH_DECAY = 0.95
        /** The compositor wants a picture this long before the refresh that shows it. */
        const val LATCH_PRESENT = 0.004
        const val LATCH_MARGIN = 0.006
        const val LATCH_MARGIN_STEP = 0.002
        const val LATCH_MARGIN_MAX = 0.016
        /** A late frame's extra margin is given back over this many frames on time. */
        const val LATCH_RECOVER_FRAMES = 600
        /** Shorter waits are not worth a sleep, and no wait is longer than this. */
        const val LATCH_MIN = 0.001
        const val LATCH_MAX = 0.010

        fun pack(lx: Int, ly: Int, rx: Int, ry: Int): Long =
            listOf(lx, ly, rx, ry).fold(0L) { packed, value -> (packed shl 16) or (value.toLong() and 0xffff) }

        fun axis(packed: Long, index: Int): Float = ((packed shr ((3 - index) * 16)) and 0xffff).toInt().toShort() / 32767f

        val streamVideo = staticCFunction { context: COpaquePointer?, rgba: kotlinx.cinterop.CPointer<UByteVar>?, width: Int, height: Int ->
            val sink = context!!.asStableRef<RetroPlayer>().get().sink ?: return@staticCFunction
            sink.video(NSData.dataWithBytesNoCopy(rgba, (width * height * 4).convert(), false), width, height)
        }

        val streamSurface = staticCFunction { context: COpaquePointer?, pixelBuffer: COpaquePointer? ->
            val sink = context!!.asStableRef<RetroPlayer>().get().sink ?: return@staticCFunction
            pixelBuffer?.let(sink::video)
        }

        val streamAudio = staticCFunction { context: COpaquePointer?, samples: kotlinx.cinterop.CPointer<kotlinx.cinterop.ShortVar>?, count: ULong ->
            val sink = context!!.asStableRef<RetroPlayer>().get().sink ?: return@staticCFunction
            sink.audio(NSData.dataWithBytesNoCopy(samples, (count * 4u).convert(), false), count.toInt())
        }

        val rumbleCallback = staticCFunction { context: COpaquePointer?, port: Int, weak: Float, strong: Float ->
            context!!.asStableRef<RetroPlayer>().get().rumble?.rumble(port, weak, strong)
            Unit
        }

        val outgoing = staticCFunction { context: COpaquePointer?, type: Int, frame: UInt, value: ULong ->
            val listener = context!!.asStableRef<RetroPlayer>().get().netplayListener ?: return@staticCFunction
            when (type) {
                RE_NETPLAY_LOCAL_INPUT -> listener.onLocalInput(frame.toInt(), value.toInt())
                RE_NETPLAY_STATE_HASH -> listener.onStateHash(frame.toInt(), value.toLong())
                2 -> listener.onPerformance(frame.toInt(), value.toLong())
                in 100..227 -> listener.onStateDiagnostic(frame.toInt(), type - 100, value.toLong())
            }
        }
    }
}

private class Ticker(private val onTick: (CADisplayLink) -> Unit) : NSObject() {
    @ObjCAction
    fun tick(link: CADisplayLink) = onTick(link)
}

private class GameView : UIView(frame = CGRectZero.readValue()) {
    private val pendingResize = atomic<Pair<Int, Int>?>(null)
    /** The screen this view moved to and the render thread has not followed yet. */
    private val pendingScreen = atomic<UIScreen?>(null)
    @Volatile var pixelSize: Pair<Int, Int> = 1 to 1
        private set

    init {
        backgroundColor = UIColor.blackColor
        multipleTouchEnabled = true
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        measure()
    }

    /**
     * A plain UIView reports contentScaleFactor 1 until it is in a window. Laid out before that, the drawable was
     * measured in points, so the game rendered at half resolution in a corner, and no later layout fixed it because
     * the point size never changed. Adopt the screen's scale on entering a window and measure again.
     */
    override fun didMoveToWindow() {
        super.didMoveToWindow()
        val scale = traitCollection.displayScale
        if (window != null && scale > 0.0 && scale != contentScaleFactor) contentScaleFactor = scale
        window?.windowScene?.screen?.let { pendingScreen.value = it }
        measure()
    }

    private fun measure() {
        val scale = contentScaleFactor
        val size = bounds.useContents { (size.width * scale).toInt() to (size.height * scale).toInt() }
        if (size != pixelSize) {
            pixelSize = size
            pendingResize.value = size
        }
    }

    fun takeResize(): Pair<Int, Int>? = pendingResize.getAndSet(null)

    fun takeScreen(): UIScreen? = pendingScreen.getAndSet(null)
}

private inline fun takeBytes(read: (kotlinx.cinterop.CPointer<size_tVar>) -> kotlinx.cinterop.CPointer<UByteVar>?): ByteArray? = memScoped {
    val size = alloc<size_tVar>()
    val data = read(size.ptr) ?: return null
    val bytes = data.readBytes(size.value.toInt())
    re_free(data)
    bytes
}

private inline fun takeString(read: () -> kotlinx.cinterop.CPointer<ByteVar>?): String? {
    val data = read() ?: return null
    val text = data.toKString()
    re_free(data)
    return text
}

private inline fun <T> ByteArray.load(block: (kotlinx.cinterop.CPointer<UByteVar>, ULong) -> T): T =
    asUByteArray().usePinned { block(it.addressOf(0), size.convert()) }
