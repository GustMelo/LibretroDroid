package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/** All methods run on the emulation thread, including the installed receive poll. */
interface NetpacketCore {
    fun start(localId: Int, send: (Int, ByteArray, Int, Int) -> Unit, pollReceive: () -> Unit): Boolean
    fun connected(peerId: Int): Boolean
    fun receive(data: ByteArray, size: Int, sender: Int)
    fun poll()
    fun stop()
}

/**
 * Two-peer transport after compatibility negotiation. start/pump/stop belong to
 * the emulation thread. closeNetwork never invokes the core and may run anywhere.
 * Additional GBA peers require a routing topology beyond this replicated pair.
 */
class NetpacketSession(
    private val connection: TcpConnection,
    private val core: NetpacketCore,
    private val localId: Int,
) {
    private data class Packet(val flags: Int, val target: Int, val bytes: ByteArray)
    private val peerId = 1 - localId
    private val wire = NetpacketChannel(connection.source, connection.sink)
    private val outgoing = Channel<Packet>(CAPACITY)
    private val lock = SynchronizedObject()
    private val incoming = ArrayDeque<Packet>()
    private val closed = atomic(false)
    private val failure = atomic<String?>(null)
    private var started = false
    private var used = false
    val endReason: String? get() = failure.value

    init { require(localId in 0..1) }

    fun start(): Boolean {
        check(!used) { "A link session cannot be restarted" }
        used = true
        if (closed.value) return false
        started = true
        try {
            if (!core.start(localId, ::send, ::receivePending) ||
                (localId == 0 && !core.connected(peerId))) {
                closeNetwork("Core rejected link session")
                stopOnEmulationThread()
                return false
            }
            startThread("link-send") { writeLoop() }
            startThread("link-receive") { readLoop() }
            return true
        } catch (e: Exception) {
            closeNetwork(e.message ?: "Link startup failed")
            stopOnEmulationThread()
            return false
        }
    }

    private fun send(flags: Int, data: ByteArray, size: Int, target: Int) {
        if (closed.value) return
        if (size !in 0..NetpacketChannel.MAX_PAYLOAD || size > data.size ||
            flags and 7.inv() != 0 || flags and 3 == 3 ||
            (target != peerId && target != NetpacketChannel.BROADCAST)) {
            closeNetwork("Invalid core packet")
            return
        }
        if (size == 0) return // The writer flushes every packet.
        if (outgoing.trySend(Packet(flags, target, data.copyOf(size))).isFailure) {
            closeNetwork("Link outgoing queue overflow")
        }
    }

    private fun writeLoop() {
        try {
            runBlocking {
                for (packet in outgoing) {
                    if (closed.value) break
                    wire.send(packet.flags, packet.bytes, packet.bytes.size, packet.target)
                }
            }
        } catch (e: Exception) {
            closeNetwork(e.message ?: "Link write failed")
        }
    }

    private fun readLoop() {
        try {
            while (!closed.value) {
                val bytes = wire.read()
                require(wire.receivedTarget == localId ||
                    wire.receivedTarget == NetpacketChannel.BROADCAST) { "Wrong packet destination" }
                val accepted = synchronized(lock) {
                    if (closed.value || incoming.size == CAPACITY) false
                    else {
                        incoming.addLast(Packet(wire.receivedFlags, wire.receivedTarget,
                            bytes.copyOf(wire.receivedSize)))
                        true
                    }
                }
                if (!accepted) {
                    closeNetwork("Link incoming queue overflow")
                    break
                }
            }
        } catch (e: Exception) {
            closeNetwork(e.message ?: "Link read failed")
        }
    }

    private fun receivePending() {
        repeat(CAPACITY) {
            if (closed.value) return
            val packet = synchronized(lock) { incoming.removeFirstOrNull() } ?: return
            core.receive(packet.bytes, packet.bytes.size, peerId)
        }
    }

    fun pump() {
        if (!started) return
        if (closed.value) {
            stopOnEmulationThread()
            return
        }
        try {
            receivePending()
            if (!closed.value) core.poll()
        } catch (e: Exception) {
            closeNetwork(e.message ?: "Core callback failed")
        }
        if (closed.value) stopOnEmulationThread()
    }

    fun closeNetwork(reason: String = "Disconnected") {
        if (!closed.compareAndSet(false, true)) return
        failure.value = reason
        outgoing.cancel()
        synchronized(lock) { incoming.clear() }
        runCatching { connection.close() }
    }

    fun stopOnEmulationThread() {
        closeNetwork()
        if (!started) return
        started = false
        core.stop()
    }

    companion object {
        // At most 4 MiB queued payload per direction, independent of session age.
        private const val CAPACITY = 64
    }
}
