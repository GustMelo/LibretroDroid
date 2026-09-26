package com.libretrodroid.netplay

import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable

data class SocketAddress(val host: String, val port: Int)

expect class UdpSocket : Closeable {
    val localPort: Int

    fun send(bytes: ByteArray, length: Int, to: SocketAddress)

    fun receive(buffer: ByteArray): Received?

    override fun close()

    companion object {
        fun bind(preferredPort: Int): UdpSocket
    }
}

class Received(val length: Int, val from: SocketAddress)

expect class TcpConnection : Closeable {
    val source: BufferedSource
    val sink: BufferedSink
    val remote: SocketAddress

    override fun close()

    companion object {
        fun connect(to: SocketAddress, timeoutMillis: Int): TcpConnection
    }
}

expect class TcpServer : Closeable {
    val localPort: Int

    fun accept(): TcpConnection?

    override fun close()

    companion object {
        fun bind(preferredPort: Int): TcpServer
    }
}

internal expect fun isLocalAddress(host: String): Boolean

internal expect fun netplayLog(message: String)
