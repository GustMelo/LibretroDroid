package com.libretrodroid.netplay

import okio.Buffer
import kotlin.test.*

class NetpacketChannelTest {
    @Test fun exchangesCorePacketsThroughPlatformTcpSockets() {
        val server = TcpServer.bind(0)
        try {
            val client = TcpConnection.connect(SocketAddress("127.0.0.1", server.localPort), 1000)
            try {
                val peer = assertNotNull(server.accept())
                try {
                    val outgoing = NetpacketChannel(client.source, client.sink)
                    val incoming = NetpacketChannel(peer.source, peer.sink)
                    outgoing.send(1, byteArrayOf(0, 127, -1), 3, 0)
                    assertContentEquals(byteArrayOf(0, 127, -1), incoming.read().copyOf(incoming.receivedSize))
                    incoming.send(5, byteArrayOf(42), 1, 1)
                    assertEquals(42, outgoing.read()[0].toInt())
                    assertEquals(1, outgoing.receivedTarget)
                } finally { peer.close() }
            } finally { client.close() }
        } finally { server.close() }
    }

    @Test fun preservesBoundariesAndReusesReceiveStorage() {
        val wire = Buffer()
        val channel = NetpacketChannel(wire, wire)
        val payload = ByteArray(NetpacketChannel.MAX_PAYLOAD) { it.toByte() }
        channel.send(1, payload, payload.size, 1)
        channel.send(0, byteArrayOf(42), 1, NetpacketChannel.BROADCAST)
        val received = channel.read()
        assertContentEquals(payload, received)
        assertEquals(payload.size, channel.receivedSize)
        assertEquals(1, channel.receivedTarget)
        assertSame(received, channel.read())
        assertEquals(1, channel.receivedSize)
        assertEquals(42, received[0].toInt())
        assertEquals(NetpacketChannel.BROADCAST, channel.receivedTarget)
        assertTrue(wire.exhausted())
    }

    @Test fun rejectsUnboundedAndInvalidPackets() {
        for (length in listOf(-1, 0, 65537, Int.MAX_VALUE)) {
            val wire = Buffer().writeIntLe(length)
            assertFails { NetpacketChannel(wire, Buffer()).read() }
        }
        for (flags in listOf(3, 8, 255)) {
            val wire = Buffer().writeIntLe(1).writeByte(flags).writeShortLe(1).writeByte(0)
            assertFails { NetpacketChannel(wire, Buffer()).read() }
        }
        val wire = Buffer().writeIntLe(2).writeByte(1).writeShortLe(1).writeByte(0)
        assertFails { NetpacketChannel(wire, Buffer()).read() }
    }

    @Test fun validatesBeforeWritingAndHandlesFlushWithoutEmptyPacket() {
        val wire = Buffer()
        val channel = NetpacketChannel(wire, wire)
        assertFails { channel.send(1, byteArrayOf(0), 2, 0) }
        assertFails { channel.send(1, byteArrayOf(0), 1, 4) }
        assertFails { channel.send(3, byteArrayOf(0), 1, 0) }
        channel.send(NetpacketChannel.FLUSH, byteArrayOf(), 0, 0)
        assertEquals(0L, wire.size)
    }
}
