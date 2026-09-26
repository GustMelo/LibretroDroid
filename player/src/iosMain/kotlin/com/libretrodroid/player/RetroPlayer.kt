package com.libretrodroid.player

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
import com.libretrodroid.engine.native.re_surface_changed
import com.libretrodroid.engine.native.re_surface_created
import com.libretrodroid.engine.native.re_unserialize
import com.libretrodroid.engine.native.re_unserialize_sram
import com.libretrodroid.netplay.HostStart
import com.libretrodroid.netplay.NetplayEmulator
import com.libretrodroid.netplay.NetplayListener
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

    val widescreen: Boolean = false,
)

class RetroFrame(val pixels: NSData, val width: Int, val height: Int)

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
    @Volatile private var running = false
    @Volatile private var paused = false
    private var widescreen = game.widescreen
    private var ready = false
    private var sramOnExit: ByteArray? = null
    private val stopped = dispatch_semaphore_create(0)

    private val tasksLock = SynchronizedObject()
    private val tasks = ArrayList<() -> Unit>()

    fun setTouchButtons(mask: Int) {
        touchButtons.value = mask
    }

    fun setPaused(paused: Boolean) = onRenderThread {
        if (paused == this.paused) return@onRenderThread
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

    override fun reset() = onRenderThread { re_reset() }

    fun setWidescreen(enabled: Boolean) = onRenderThread {
        widescreen = enabled
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

    fun serializeState(): ByteArray? = onRenderThread { takeBytes { re_serialize(it) } }

    fun unserializeState(state: ByteArray): Boolean = onRenderThread { state.load { data, size -> re_unserialize(data, size) } }

    override val minInputDelay: Int get() = game.netplayMinDelay

    private var ownSaveRam: ByteArray? = null

    override fun startAsHost(players: Int, inputDelay: Int): HostStart = onRenderThread {

        re_netplay_start(0, players, inputDelay, HASH_INTERVAL, rollback)
        HostStart(
            state = takeBytes { re_serialize(it) } ?: ByteArray(0),
            saveRam = takeBytes { re_serialize_sram(it) }?.takeIf { it.isNotEmpty() },
        )
    }

    override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?): Boolean = onRenderThread {

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
        re_set_buttons(0u, (gamepads.buttons or touchButtons.value).toUShort())
        re_frame(outgoing, self)
        if (!ready) {
            ready = true
            dispatch_async(dispatch_get_main_queue()) { onReady?.invoke() }
        }
    }

    private fun applyAspectRatio() {
        val (w, h) = (view as GameView).pixelSize
        re_set_aspect_ratio_override(if (widescreen && h > 1) w.toFloat() / h else 0f)
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
