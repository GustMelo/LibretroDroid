package com.libretrodroid.netplay

import kotlin.test.*

class NetpacketSessionTest {
    private class Core : NetpacketCore {
        lateinit var send: (Int, ByteArray, Int, Int) -> Unit
        val received = mutableListOf<ByteArray>()
        var stops = 0
        var connected = 0
        override fun start(localId: Int, send: (Int, ByteArray, Int, Int) -> Unit, pollReceive: () -> Unit): Boolean {
            this.send = send
            return true
        }
        override fun connected(peerId: Int): Boolean { connected++; return true }
        override fun receive(data: ByteArray, size: Int, sender: Int) { received += data.copyOf(size) }
        override fun poll() = Unit
        override fun stop() { stops++ }
    }

    @Test fun pairedSessionsDeliverAndStopOnlyDuringEmulationPump() {
        val server = TcpServer.bind(0)
        val client = TcpConnection.connect(SocketAddress("127.0.0.1", server.localPort), 1000)
        val peer = assertNotNull(server.accept())
        val hostCore = Core()
        val clientCore = Core()
        val host = NetpacketSession(peer, hostCore, 0)
        val guest = NetpacketSession(client, clientCore, 1)
        try {
            assertTrue(host.start())
            assertTrue(guest.start())
            assertEquals(1, hostCore.connected)
            assertEquals(0, clientCore.connected)
            val data = byteArrayOf(42, -1)
            hostCore.send(1, data, data.size, 1)
            data[0] = 0 // The queue owns a copy, not the core's transient buffer.
            val deadline = nanoTime() + 3_000_000_000L
            while (clientCore.received.isEmpty() && nanoTime() < deadline) {
                guest.pump()
                sleepMillis(1)
            }
            assertContentEquals(byteArrayOf(42, -1), clientCore.received.single())
            guest.closeNetwork()
            assertEquals(0, clientCore.stops)
            guest.pump()
            guest.pump()
            assertEquals(1, clientCore.stops)
        } finally {
            host.stopOnEmulationThread()
            guest.stopOnEmulationThread()
            server.close()
        }
    }
}
