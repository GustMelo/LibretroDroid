package com.libretrodroid.netplay

import android.util.Log
import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable
import okio.buffer
import okio.sink
import okio.source
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

private const val DSCP_EF = 0xB8
private const val UDP_BUFFER_BYTES = 256 * 1024

actual class UdpSocket private constructor(private val socket: DatagramSocket) : Closeable {
    actual val localPort: Int get() = socket.localPort

    private val sendLock = Any()
    private val destinations = HashMap<SocketAddress, InetSocketAddress>()
    private val outgoing = DatagramPacket(ByteArray(0), 0)

    actual fun send(bytes: ByteArray, length: Int, to: SocketAddress) = synchronized(sendLock) {
        outgoing.setData(bytes, 0, length)
        outgoing.socketAddress = destinations.getOrPut(to) { InetSocketAddress(to.host, to.port) }
        socket.send(outgoing)
    }

    private val packet = DatagramPacket(ByteArray(0), 0)

    actual fun receive(buffer: ByteArray): Received? {
        packet.setData(buffer, 0, buffer.size)
        try {
            socket.receive(packet)
        } catch (e: Exception) {
            if (socket.isClosed) return null
            throw e
        }
        val from = packet.socketAddress as InetSocketAddress
        return Received(packet.length, SocketAddress(from.address.hostAddress!!, from.port))
    }

    actual override fun close() = socket.close()

    actual companion object {
        actual fun bind(preferredPort: Int): UdpSocket = UdpSocket(DatagramSocket(null).apply {
            runCatching { bind(InetSocketAddress(preferredPort)) }.onFailure { bind(InetSocketAddress(0)) }
            runCatching { trafficClass = DSCP_EF }
            receiveBufferSize = UDP_BUFFER_BYTES
            sendBufferSize = UDP_BUFFER_BYTES
        })
    }
}

actual class TcpConnection(private val socket: Socket) : Closeable {
    actual val source: BufferedSource = socket.source().buffer()
    actual val sink: BufferedSink = socket.sink().buffer()
    actual val remote: SocketAddress = SocketAddress(socket.inetAddress.hostAddress!!, socket.port)

    actual override fun close() = socket.close()

    actual companion object {
        actual fun connect(to: SocketAddress, timeoutMillis: Int): TcpConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(to.host, to.port), timeoutMillis)
            return TcpConnection(socket)
        }
    }
}

actual class TcpServer private constructor(private val server: ServerSocket) : Closeable {
    actual val localPort: Int get() = server.localPort

    actual fun accept(): TcpConnection? {
        val client = runCatching { server.accept() }.getOrNull() ?: return null
        client.tcpNoDelay = true
        return TcpConnection(client)
    }

    actual override fun close() = server.close()

    actual companion object {
        actual fun bind(preferredPort: Int): TcpServer =
            TcpServer(runCatching { ServerSocket(preferredPort) }.getOrElse { ServerSocket(0) })
    }
}

internal actual fun isLocalAddress(host: String): Boolean = runCatching {
    val address = InetAddress.getByName(host)
    address.isLoopbackAddress || address.isAnyLocalAddress || NetworkInterface.getByInetAddress(address) != null
}.getOrDefault(false)

internal actual fun netplayLog(message: String) {
    Log.i("Netplay", message)
}
