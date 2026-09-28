package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import okio.Closeable

/**
 * Keeps a paired connection alive when the core carries the link data on its own socket
 * (Gambatte's GB serial). A heartbeat each second makes a vanished peer surface as a write
 * failure instead of an idle socket that never closes. [onClosed] runs once, on a background thread.
 */
class LinkControl(
    private val connection: TcpConnection,
    private val onClosed: (String) -> Unit,
) : Closeable {
    private val closed = atomic(false)

    fun start() {
        startThread("link-control-read") {
            try {
                while (!closed.value) connection.source.readByte()
            } catch (e: Exception) {
                end("Peer disconnected")
            }
        }
        startThread("link-control-beat") {
            try {
                while (!closed.value) {
                    connection.sink.writeByte(HEARTBEAT).flush()
                    sleepMillis(HEARTBEAT_MS)
                }
            } catch (e: Exception) {
                end("Peer disconnected")
            }
        }
    }

    private fun end(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.close() }
        onClosed(reason)
    }

    /** Closes without reporting: the local player chose to leave. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.close() }
    }

    private companion object {
        const val HEARTBEAT = 0
        const val HEARTBEAT_MS = 1000L
    }
}
