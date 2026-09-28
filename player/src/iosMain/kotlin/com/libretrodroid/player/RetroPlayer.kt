package com.libretrodroid.player

import com.libretrodroid.netplay.NetpacketSession
import com.libretrodroid.netplay.TcpConnection
import com.libretrodroid.engine.native.RE_NETPLAY_LOCAL_INPUT
import com.libretrodroid.engine.native.RE_NETPLAY_STATE_HASH
import com.libretrodroid.engine.native.RE_SHADER_SHARP
import com.libretrodroid.engine.native.re_attach_layer
import com.libretrodroid.engine.native.re_capture
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
import com.libretrodroid.engine.native.re_set_motion
import com.libretrodroid.engine.native.re_set_multitap
import com.libretrodroid.engine.native.re_stream_frame
import com.libretrodroid.engine.native.re_stream_stop
import com.libretrodroid.engine.native.re_surface_changed
import com.libretrodroid.engine.native.re_surface_created
import com.libretrodroid.engine.native.re_unserialize
import com.libretrodroid.engine.native.re_unserialize_sram
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
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAFrameRateRangeMake
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.size_tVar
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
)

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

    /** Up to four players on cores with a multitap; returns whether it is active. */
    fun setMultitap(enabled: Boolean): Boolean = onRenderThread { re_set_multitap(enabled) }

    /** Streams at [width] x [height] (letterboxed) until [stopStream]. The screen keeps playing too. */
    fun startStream(width: Int, height: Int, sink: RetroStreamSink) = onRenderThread {
        stopStreamOnRenderThread()
        streamSize = width to height
        this.sink = sink
        val ref = StableRef.create(this).also { sinkRef = it }
        re_set_audio_tap(streamAudio, ref.asCPointer())
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

    fun setWidescreen(enabled: Boolean) = setAspectRatio(if (enabled) AspectRatio.FILL else AspectRatio.CORE)

    /** [AspectRatio.CORE], [AspectRatio.FILL] or a fixed width/height ratio such as 16/9. */
    fun setAspectRatio(ratio: Float) = onRenderThread {
        aspectRatio = ratio
        applyAspectRatio()

        if (paused) re_redraw()
    }

    fun disks(): Pair<Int, Int> = onRenderThread { re_disk_count() to re_disk_current() }

    fun setDisk(index: Int) = onRenderThread { re_disk_set(index.toUInt()) }

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

    /**
     * Players sharing this device on a retrolink core: [players] linked consoles, controller port p on console p,
     * shown side by side (a 2x2 grid for 3 or 4). 1 goes back to a single console.
     */
    fun setLocalLinkPlayers(players: Int): Boolean = onRenderThread {
        val linked = re_link_set_players(players)
        re_link_set_grid(linked && players > 1)
        linked
    }

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

    private fun renderThread(layer: kotlinx.cinterop.CPointer<kotlinx.cinterop.CPointed>?) {
        val self = StableRef.create(this)
        val ticker = Ticker { frame(self.asCPointer()) }
        var link: CADisplayLink? = null
        try {
            if (!open(layer)) {
                fail(re_last_error()?.toKString() ?: "failed to open the game")
                return
            }
            link = CADisplayLink.displayLinkWithTarget(ticker, NSSelectorFromString("tick:")).apply {

                preferredFrameRateRange = CAFrameRateRangeMake(60f, 60f, 60f)
                addToRunLoop(NSRunLoop.currentRunLoop, NSRunLoopCommonModes)
            }
            while (running) {
                NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.1))
            }
        } finally {
            link?.invalidate()
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
        val config = alloc<re_config>().apply {
            core_path = game.corePath.cstr.ptr
            system_dir = game.systemDir.cstr.ptr
            saves_dir = game.savesDir.cstr.ptr
            language = (NSLocale.currentLocale.languageCode ?: "en").cstr.ptr
            refresh_rate = 60f
            shader = RE_SHADER_SHARP
        }
        if (!re_create(config.ptr) || !re_load_game(game.gamePath)) return false
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

    private fun frame(self: COpaquePointer) {
        runPendingTasks()
        (view as GameView).takeResize()?.let { (w, h) ->
            re_surface_changed(w, h)
            applyAspectRatio()
            if (paused) re_redraw()
        }
        if (paused) return
        val local = gamepads.buttons or touchButtons.value
        for (port in 0 until PORTS) {
            val mask = remoteButtons[port].value or if (port == localPort) local else 0
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
        if (sink != null) streamSize.let { (w, h) ->
            // FILL means the frame's shape here, not the phone's: the TV would get a narrow portrait picture.
            if (aspectRatio == AspectRatio.FILL) re_set_aspect_ratio_override(overrideFor(w, h))
            re_stream_frame(w, h, streamVideo, self)
            if (aspectRatio == AspectRatio.FILL) applyAspectRatio()
        }
        if (!ready) {
            ready = true
            dispatch_async(dispatch_get_main_queue()) { onReady?.invoke() }
        }
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

        fun pack(lx: Int, ly: Int, rx: Int, ry: Int): Long =
            listOf(lx, ly, rx, ry).fold(0L) { packed, value -> (packed shl 16) or (value.toLong() and 0xffff) }

        fun axis(packed: Long, index: Int): Float = ((packed shr ((3 - index) * 16)) and 0xffff).toInt().toShort() / 32767f

        val streamVideo = staticCFunction { context: COpaquePointer?, rgba: kotlinx.cinterop.CPointer<UByteVar>?, width: Int, height: Int ->
            val sink = context!!.asStableRef<RetroPlayer>().get().sink ?: return@staticCFunction
            sink.video(NSData.dataWithBytesNoCopy(rgba, (width * height * 4).convert(), false), width, height)
        }

        val streamAudio = staticCFunction { context: COpaquePointer?, samples: kotlinx.cinterop.CPointer<kotlinx.cinterop.ShortVar>?, count: ULong ->
            val sink = context!!.asStableRef<RetroPlayer>().get().sink ?: return@staticCFunction
            sink.audio(NSData.dataWithBytesNoCopy(samples, (count * 4u).convert(), false), count.toInt())
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

private class Ticker(private val onTick: () -> Unit) : NSObject() {
    @ObjCAction
    fun tick(link: CADisplayLink) = onTick()
}

private class GameView : UIView(frame = CGRectZero.readValue()) {
    private val pendingResize = atomic<Pair<Int, Int>?>(null)
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
}

private inline fun takeBytes(read: (kotlinx.cinterop.CPointer<size_tVar>) -> kotlinx.cinterop.CPointer<UByteVar>?): ByteArray? = memScoped {
    val size = alloc<size_tVar>()
    val data = read(size.ptr) ?: return null
    val bytes = data.readBytes(size.value.toInt())
    re_free(data)
    bytes
}

private inline fun <T> ByteArray.load(block: (kotlinx.cinterop.CPointer<UByteVar>, ULong) -> T): T =
    asUByteArray().usePinned { block(it.addressOf(0), size.convert()) }
