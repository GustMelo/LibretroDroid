package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import kotlin.test.*

class LinkControlTest {
    @Test fun peerLeavingIsReportedOnceAndLocalCloseIsSilent() {
        val server = TcpServer.bind(0)
        val client = TcpConnection.connect(SocketAddress("127.0.0.1", server.localPort), 1000)
        val peer = assertNotNull(server.accept())
        val remoteEnds = atomic(0)
        val localEnds = atomic(0)
        val remote = LinkControl(peer) { remoteEnds.incrementAndGet() }
        val local = LinkControl(client) { localEnds.incrementAndGet() }
        try {
            remote.start()
            local.start()
            sleepMillis(50)
            assertEquals(0, remoteEnds.value)
            local.close()
            val deadline = nanoTime() + 3_000_000_000L
            while (remoteEnds.value == 0 && nanoTime() < deadline) sleepMillis(5)
            assertEquals(1, remoteEnds.value)
            assertEquals(0, localEnds.value)
        } finally {
            remote.close()
            local.close()
            server.close()
        }
    }
}
