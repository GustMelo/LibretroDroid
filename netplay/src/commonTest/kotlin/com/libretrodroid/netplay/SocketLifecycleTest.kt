package com.libretrodroid.netplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SocketLifecycleTest {
    @Test
    fun closingAnOldConnectionDoesNotCloseAReusedDescriptor() {
        val server = TcpServer.bind(0)
        val address = SocketAddress("127.0.0.1", server.localPort)
        try {
            val old = TcpConnection.connect(address, 1000)
            val oldPeer = assertNotNull(server.accept())
            old.close()
            val replacement = TcpConnection.connect(address, 1000)
            val peer = assertNotNull(server.accept())
            try {
                old.close()
                replacement.sink.writeByte(42).flush()
                assertEquals(42, peer.source.readByte().toInt())
            } finally {
                replacement.close()
                peer.close()
                oldPeer.close()
            }
        } finally {
            server.close()
        }
    }
}
