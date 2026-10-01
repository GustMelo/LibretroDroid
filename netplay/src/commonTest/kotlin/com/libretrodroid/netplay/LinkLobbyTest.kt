package com.libretrodroid.netplay

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.test.*

class LinkLobbyTest {
    /** One shared LAN: every advertisement reaches every browser, with the host rewritten to loopback. */
    private class Lan {
        val lock = SynchronizedObject()
        val games = mutableListOf<LanGame>()
        val browsers = mutableListOf<(LanGame) -> Unit>()

        fun discovery() = object : LanDiscovery {
            var mine: LanGame? = null
            override fun advertise(game: LanGame) {
                val listeners = synchronized(lock) { mine = game; games += game; browsers.toList() }
                listeners.forEach { it(game) }
            }
            override fun stopAdvertising() { synchronized(lock) { games.remove(mine) } }
            override fun discover(onLost: (String) -> Unit, onFound: (LanGame) -> Unit) {
                val known = synchronized(lock) { browsers += onFound; games.toList() }
                known.forEach(onFound)
            }
            override fun close() = stopAdvertising()
        }
    }

    private class Result {
        val lock = SynchronizedObject()
        var peer: LinkPeer? = null
        fun set(value: LinkPeer) = synchronized(lock) { peer = value }
        fun get() = synchronized(lock) { peer }
    }

    private fun await(vararg results: Result) {
        val deadline = nanoTime() + 5_000_000_000L
        while (results.any { it.get() == null } && nanoTime() < deadline) sleepMillis(5)
    }

    @Test fun sameGamePairsOnceWithOppositeRoles() {
        val lan = Lan()
        val first = Result()
        val second = Result()
        val a = LinkLobby("rom|core|1", "A", lan.discovery(), first::set)
        a.start()
        sleepMillis(5) // A started first, so A hosts.
        val b = LinkLobby("rom|core|1", "B", lan.discovery(), second::set)
        b.start()
        try {
            await(first, second)
            val host = assertNotNull(first.get())
            val guest = assertNotNull(second.get())
            assertEquals(0, host.localId)
            assertEquals(1, guest.localId)
            assertEquals("B", host.peerName)
            assertEquals("A", guest.peerName)
            guest.connection.sink.writeByte(7).flush()
            assertEquals(7, host.connection.source.readByte().toInt())
        } finally {
            a.close(); b.close()
            first.get()?.connection?.close()
            second.get()?.connection?.close()
        }
    }

    @Test fun differentCoreVersionNeverPairs() {
        val lan = Lan()
        val first = Result()
        val second = Result()
        val a = LinkLobby("rom|core|1", "A", lan.discovery(), first::set)
        a.start()
        sleepMillis(5)
        val b = LinkLobby("rom|core|2", "B", lan.discovery(), second::set)
        b.start()
        try {
            sleepMillis(300)
            assertNull(first.get())
            assertNull(second.get())
        } finally {
            a.close(); b.close()
        }
    }
}
