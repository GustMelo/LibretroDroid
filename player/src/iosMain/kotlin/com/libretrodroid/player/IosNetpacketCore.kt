package com.libretrodroid.player

import com.libretrodroid.engine.native.*
import com.libretrodroid.netplay.NetpacketCore
import kotlinx.cinterop.*

/** Owned and called exclusively by the player's emulation thread. */
class IosNetpacketCore : NetpacketCore {
    private class Callbacks(
        val send: (Int, ByteArray, Int, Int) -> Unit,
        val poll: () -> Unit,
    ) {
        var failure: Throwable? = null
    }
    private var callbacks: StableRef<Callbacks>? = null

    override fun start(
        localId: Int,
        send: (Int, ByteArray, Int, Int) -> Unit,
        pollReceive: () -> Unit,
    ): Boolean {
        check(callbacks == null)
        require(localId in 0..3)
        val reference = StableRef.create(Callbacks(send, pollReceive))
        callbacks = reference
        re_netpacket_set_transport(reference.asCPointer(), sendCallback)
        re_netpacket_set_poll(pollCallback)
        if (!re_netpacket_start(localId.toUShort())) {
            stop()
            return false
        }
        checkCallbackFailure()
        return true
    }

    override fun connected(peerId: Int): Boolean {
        require(peerId in 0..3)
        val accepted = re_netpacket_connected(peerId.toUShort())
        checkCallbackFailure()
        return accepted
    }

    override fun receive(data: ByteArray, size: Int, sender: Int) {
        require(size in 1..65536 && size <= data.size && sender in 0..3)
        data.usePinned { re_netpacket_receive(it.addressOf(0), size.toULong(), sender.toUShort()) }
        checkCallbackFailure()
    }

    override fun poll() {
        re_netpacket_poll()
        checkCallbackFailure()
    }

    override fun stop() {
        if (callbacks == null) return
        // The native bridge must no longer retain our StableRef when disposed.
        re_netpacket_stop()
        callbacks?.dispose()
        callbacks = null
    }

    private fun checkCallbackFailure() {
        callbacks?.get()?.failure?.let { throw IllegalStateException("Core transport callback failed", it) }
    }

    companion object {
        private val sendCallback = staticCFunction {
            context: COpaquePointer?, flags: Int, data: COpaquePointer?,
            size: ULong, target: UShort, broadcast: Boolean ->
            val state = context?.asStableRef<Callbacks>()?.get()
            if (state != null && state.failure == null) {
                try {
                    require(size <= 65536uL && (data != null || size == 0uL))
                    val bytes = data?.reinterpret<ByteVar>()?.readBytes(size.toInt()) ?: ByteArray(0)
                    state.send(flags, bytes, bytes.size, if (broadcast) 65535 else target.toInt())
                } catch (failure: Throwable) {
                    // Kotlin exceptions must never unwind through the C core.
                    state.failure = failure
                }
            }
        }
        private val pollCallback = staticCFunction { context: COpaquePointer? ->
            val state = context?.asStableRef<Callbacks>()?.get()
            if (state != null && state.failure == null) {
                try { state.poll() } catch (failure: Throwable) { state.failure = failure }
            }
        }
    }
}
