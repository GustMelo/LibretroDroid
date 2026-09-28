package com.libretrodroid.netplay

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class MessageNetworkTest {
    private class Emulator : NetplayEmulator {
        private val lock = SynchronizedObject()
        private val received = mutableListOf<Triple<Int, Int, Int>>()
        override val rollback = false
        override fun startAsHost(players: Int, inputDelay: Int) = HostStart(ByteArray(300_000) { it.toByte() }, null)
        override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?) =
            state.size == 300_000 && state[299_999] == 299_999.toByte()
        override fun stop() = Unit
        override fun reset() = Unit
        override fun pushInput(port: Int, frame: Int, buttons: Int) {
            synchronized(lock) { received += Triple(port, frame, buttons) }
        }
        fun inputs() = synchronized(lock) { received.toList() }
    }

    private class Statuses {
        private val lock = SynchronizedObject()
        private var status: NetplayStatus = NetplayStatus.Solo
        fun set(value: NetplayStatus) = synchronized(lock) { status = value }
        fun get() = synchronized(lock) { status }
    }

    /** Both directions of one link, like a pair of WebRTC data channels; [lossy] drops 30% of the datagrams. */
    private class Wire(private val lossy: Boolean = false) {
        private val random = kotlin.random.Random(42)
        lateinit var hostEnds: MessageNetwork.Ends
        lateinit var guestEnds: MessageNetwork.Ends
        fun hostSide() = side { guestEnds }
        fun guestSide() = side { hostEnds }
        private fun side(other: () -> MessageNetwork.Ends) = object : MessageNetwork.Link {
            override fun sendReliable(bytes: ByteArray): Boolean { other().reliable(bytes); return true }
            override fun sendDatagram(bytes: ByteArray): Boolean {
                if (!lossy || random.nextInt(10) >= 3) other().datagram(bytes)
                return true
            }
            override fun close() { other().closed() }
        }
    }

    private val sessions = mutableListOf<NetplaySession>()

    @AfterTest
    fun tearDown() = sessions.forEach { it.close() }

    @Test
    fun aGuestJoinsOverALinkAndInputsFlowEvenWhenDatagramsAreLost() {
        val hostNetwork = MessageNetwork()
        val guestNetwork = MessageNetwork()
        val hostEmulator = Emulator()
        val guestEmulator = Emulator()
        val hostStatus = Statuses()
        val guestStatus = Statuses()
        val host = NetplaySession(hostEmulator, "game", "host", network = hostNetwork) { hostStatus.set(it) }.also { sessions += it }
        val guest = NetplaySession(guestEmulator, "game", "guest", network = guestNetwork) { guestStatus.set(it) }.also { sessions += it }
        host.host()

        val wire = Wire(lossy = true)
        wire.hostEnds = hostNetwork.attach(wire.hostSide())
        wire.guestEnds = guestNetwork.attach(wire.guestSide())
        guest.join(LanGame("online", "game", "host", MessageNetwork.address(0), 0))

        waitUntil { hostStatus.get() is NetplayStatus.Playing && guestStatus.get() is NetplayStatus.Playing }
        assertEquals(1, (guestStatus.get() as NetplayStatus.Playing).port)

        repeat(30) { frame ->
            host.onLocalInput(frame, 0x10 + frame)
            guest.onLocalInput(frame, 0x20 + frame)
        }
        waitUntil {
            (0 until 30).all { Triple(1, it, 0x20 + it) in hostEmulator.inputs() && Triple(0, it, 0x10 + it) in guestEmulator.inputs() }
        }

        wire.guestSide().close()
        waitUntil { hostStatus.get() == NetplayStatus.Solo }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = nanoTime() + 10_000_000_000L
        while (!condition()) {
            if (nanoTime() > deadline) fail("timed out")
            sleepMillis(10)
        }
    }
}
