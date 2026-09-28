package com.libretrodroid.netplay

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

class NetplaySessionTest {
    private class FakeEmulator(
        override val rollback: Boolean = true,
        override val canRecompile: Boolean = false,
    ) : NetplayEmulator {
        private val lock = SynchronizedObject()
        private val received = mutableListOf<Triple<Int, Int, Int>>()
        var clientState: ByteArray? = null
        var clientSaveRam: ByteArray? = null
        var clientPort = -1
        @kotlin.concurrent.Volatile var clientStarts = 0
        @kotlin.concurrent.Volatile var recompiler: Boolean? = null

        override fun startAsHost(players: Int, inputDelay: Int) = HostStart(STATE, SAVE_RAM)
        override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?): Boolean {
            clientPort = port
            clientStarts++
            clientState = state
            clientSaveRam = saveRam
            return true
        }
        override fun stop() = Unit
        @kotlin.concurrent.Volatile var resets = 0
        override fun reset() { resets++ }
        override fun pushInput(port: Int, frame: Int, buttons: Int) {
            synchronized(lock) { received += Triple(port, frame, buttons) }
        }
        fun inputs() = synchronized(lock) { received.toList() }
        override fun setRecompiler(enabled: Boolean) { recompiler = enabled }
    }

    private class Statuses {
        private val lock = SynchronizedObject()
        private val list = mutableListOf<NetplayStatus>()
        fun add(status: NetplayStatus) = synchronized(lock) { list += status }
        fun last() = synchronized(lock) { list.lastOrNull() }
    }

    private val sessions = mutableListOf<NetplaySession>()

    @AfterTest
    fun tearDown() = sessions.forEach { it.close() }

    private fun session(emulator: NetplayEmulator, gameKey: String, name: String, statuses: Statuses) =
        NetplaySession(emulator, gameKey, name) { statuses.add(it) }.also { sessions += it }

    /** A retrolink core: records the consoles the session lays out and which one this device plays. */
    private class LinkedEmulator(private val save: ByteArray) : NetplayEmulator {
        private val lock = SynchronizedObject()
        private val layouts = mutableListOf<Triple<Int, Map<Int, List<Byte>>, Boolean>>()
        @kotlin.concurrent.Volatile var local = 0
        @kotlin.concurrent.Volatile var clientSaveRam: ByteArray? = byteArrayOf(-1)
        override val rollback = false
        override val linkMaxPlayers = 4
        override fun startAsHost(players: Int, inputDelay: Int) = HostStart(STATE, SAVE_RAM)
        override fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?): Boolean {
            clientSaveRam = saveRam
            return true
        }
        override fun stop() = Unit
        override fun reset() = Unit
        override fun pushInput(port: Int, frame: Int, buttons: Int) = Unit
        override fun linkConsoles(players: Int, saves: Map<Int, ByteArray>, rebuild: Boolean) {
            synchronized(lock) { layouts += Triple(players, saves.mapValues { it.value.toList() }, rebuild) }
        }
        override fun linkConsoleSave(port: Int) = "current $port".encodeToByteArray()
        override fun setLinkLocal(port: Int) { local = port }
        override fun linkLocalSave() = save
        fun layouts() = synchronized(lock) { layouts.toList() }
    }

    @Test
    fun linkedConsolesGetEachJoinersOwnSaveAndNoSharedSaveRam() {
        val hostEmulator = LinkedEmulator("host save".encodeToByteArray())
        val clientEmulator = LinkedEmulator("client save".encodeToByteArray())
        val hostStatus = Statuses()
        val clientStatus = Statuses()
        val host = session(hostEmulator, "game", "host", hostStatus)
        val client = session(clientEmulator, "game", "client", clientStatus)

        client.join(LanGame("s", "game", "host", SocketAddress("127.0.0.1", host.host()), 0))
        waitUntil { clientStatus.last() is NetplayStatus.Playing && hostStatus.last() is NetplayStatus.Playing }

        val (players, saves, rebuild) = hostEmulator.layouts().single()
        assertEquals(2, players)
        assertEquals(false, rebuild)
        assertEquals(mapOf(1 to "client save".encodeToByteArray().toList()), saves)
        assertEquals(0, hostEmulator.local)
        assertEquals(1, clientEmulator.local)
        assertEquals(null, clientEmulator.clientSaveRam)

        client.close()
        waitUntil { hostStatus.last() == NetplayStatus.Solo }
        assertEquals(Triple(1, emptyMap<Int, List<Byte>>(), false), hostEmulator.layouts().last())
    }

    @Test
    fun clientJoinsWithHostStateAndInputsFlowBothWays() {
        val hostEmulator = FakeEmulator()
        val clientEmulator = FakeEmulator()
        val hostStatus = Statuses()
        val clientStatus = Statuses()
        val host = session(hostEmulator, "game", "host", hostStatus)
        val client = session(clientEmulator, "game", "client", clientStatus)

        val port = host.host()
        client.join(LanGame("s", "game", "host", SocketAddress("127.0.0.1", port), 0))

        waitUntil { clientStatus.last() is NetplayStatus.Playing && hostStatus.last() is NetplayStatus.Playing }
        assertContentEquals(STATE, clientEmulator.clientState)

        assertContentEquals(SAVE_RAM, clientEmulator.clientSaveRam)
        assertEquals(1, clientEmulator.clientPort)
        assertEquals(2, (hostStatus.last() as NetplayStatus.Playing).players)

        client.onLocalInput(frame = 0, buttons = 0x0101)
        host.onLocalInput(frame = 0, buttons = 0x0042)
        waitUntil { Triple(1, 0, 0x0101) in hostEmulator.inputs() && Triple(0, 0, 0x0042) in clientEmulator.inputs() }
    }

    @Test
    fun clientRestartRequestResetsHostAndResendsState() {
        val hostEmulator = FakeEmulator()
        val clientEmulator = FakeEmulator()
        val clientStatus = Statuses()
        val host = session(hostEmulator, "game", "host", Statuses())
        val client = session(clientEmulator, "game", "client", clientStatus)

        client.join(LanGame("s", "game", "host", SocketAddress("127.0.0.1", host.host()), 0))
        waitUntil { clientStatus.last() is NetplayStatus.Playing }

        client.restart()
        waitUntil { hostEmulator.resets == 1 && clientEmulator.clientStarts == 2 }
        assertEquals(0, clientEmulator.resets)
    }

    @Test
    fun mixedSessionRunsTheInterpreterEverywhere() {

        val hostEmulator = FakeEmulator(canRecompile = true)
        val clientEmulator = FakeEmulator(canRecompile = false)
        val clientStatus = Statuses()
        val host = session(hostEmulator, "ps1", "android", Statuses())
        val client = session(clientEmulator, "ps1", "iphone", clientStatus)

        client.join(LanGame("s", "ps1", "android", SocketAddress("127.0.0.1", host.host()), 0))

        waitUntil { clientStatus.last() is NetplayStatus.Playing }
        assertEquals(false, hostEmulator.recompiler)
        assertEquals(false, clientEmulator.recompiler)

        client.close()
        waitUntil { hostEmulator.recompiler == true }
    }

    @Test
    fun differentGameIsRejected() {
        val clientStatus = Statuses()
        val host = session(FakeEmulator(), "game-a", "host", Statuses())
        val client = session(FakeEmulator(), "game-b", "client", clientStatus)

        client.join(LanGame("s", "game-b", "host", SocketAddress("127.0.0.1", host.host()), 0))

        waitUntil { clientStatus.last() is NetplayStatus.Ended }
        assertEquals(NetplayEnd.Rejected(RejectReason.GAME, RejectReason.GAME.wire), (clientStatus.last() as NetplayStatus.Ended).reason)
    }

    @Test
    fun previousProtocolIsRejectedBeforeStartingEmulation() {
        val host = session(FakeEmulator(), "game", "host", Statuses())
        val connection = TcpConnection.connect(SocketAddress("127.0.0.1", host.host()), 1000)
        try {
            connection.sink.write(ControlMessage.encode(ControlMessage.Hello(NetplayProtocol.VERSION - 1, "game", "old", 12345))).flush()
            assertEquals(ControlMessage.Reject(RejectReason.APP_VERSION.wire), ControlMessage.read(connection.source))
        } finally {
            connection.close()
        }
    }

    @Test
    fun fourPlayersRelayInputsAndRejectFifth() {
        val emulators = List(4) { FakeEmulator() }
        val statuses = List(4) { Statuses() }
        val group = List(4) { session(emulators[it], "game", "peer$it", statuses[it]) }
        val address = SocketAddress("127.0.0.1", group[0].host())
        for (index in 1..3) {
            group[index].join(LanGame("s", "game", "host", address, 0))
            waitUntil { (statuses[0].last() as? NetplayStatus.Playing)?.players == index + 1 &&
                (statuses[index].last() as? NetplayStatus.Playing)?.players == index + 1 }
        }
        waitUntil { statuses.all { (it.last() as? NetplayStatus.Playing)?.players == 4 } }
        repeat(4) { group[it].onLocalInput(0, 100 + it) }
        waitUntil {
            (0..3).all { receiver -> (0..3).all { sender ->
                sender == receiver || Triple(sender, 0, 100 + sender) in emulators[receiver].inputs()
            } }
        }
        val rejected = Statuses()
        session(FakeEmulator(), "game", "fifth", rejected).join(LanGame("s", "game", "host", address, 0))
        waitUntil { rejected.last() is NetplayStatus.Ended }
        assertEquals(RejectReason.FULL, ((rejected.last() as NetplayStatus.Ended).reason as NetplayEnd.Rejected).reason)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (nanoTime() > deadline) fail("condition did not happen within 5 s")
            sleepMillis(10)
        }
    }

    private companion object {
        val STATE = byteArrayOf(1, 2, 3, 4)
        val SAVE_RAM = byteArrayOf(9, 8, 7)
    }
}
