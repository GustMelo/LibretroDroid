package com.libretrodroid.netplay

import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable

/**
 * How a netplay session reaches its peers: the LAN ([LanNetwork], UDP and TCP) or message links such as WebRTC
 * data channels ([MessageNetwork]) for sessions across the internet. Datagrams carry inputs and pings and may be
 * lost; streams carry control messages and states in order.
 */
interface NetplayNetwork : Closeable {
    val datagrams: NetplayDatagrams

    /** Accepts players who join this device's session. */
    fun listen(): StreamServer

    fun connect(address: SocketAddress, timeoutMillis: Int): StreamConnection
}

interface NetplayDatagrams : Closeable {
    val localPort: Int

    fun send(bytes: ByteArray, length: Int, to: SocketAddress)

    /** The next datagram, or null once closed. */
    fun receive(buffer: ByteArray): Received?
}

interface StreamConnection : Closeable {
    val source: BufferedSource
    val sink: BufferedSink
    val remote: SocketAddress
}

interface StreamServer : Closeable {
    val localPort: Int

    /** The next connection, or null once closed. */
    fun accept(): StreamConnection?
}

/** The LAN: UDP datagrams and TCP streams on the netplay ports. */
class LanNetwork(udpPort: Int = NetplaySession.UDP_PORT, private val tcpPort: Int = NetplaySession.TCP_PORT) : NetplayNetwork {
    private val udp = UdpSocket.bind(udpPort)

    override val datagrams: NetplayDatagrams = object : NetplayDatagrams {
        override val localPort: Int get() = udp.localPort
        override fun send(bytes: ByteArray, length: Int, to: SocketAddress) = udp.send(bytes, length, to)
        override fun receive(buffer: ByteArray): Received? = udp.receive(buffer)
        override fun close() = udp.close()
    }

    override fun listen(): StreamServer {
        val server = TcpServer.bind(tcpPort)
        return object : StreamServer {
            override val localPort: Int get() = server.localPort
            override fun accept(): StreamConnection? = server.accept()?.let(::TcpStream)
            override fun close() = server.close()
        }
    }

    override fun connect(address: SocketAddress, timeoutMillis: Int): StreamConnection =
        TcpStream(TcpConnection.connect(address, timeoutMillis))

    override fun close() = udp.close()

    private class TcpStream(private val connection: TcpConnection) : StreamConnection {
        override val source: BufferedSource get() = connection.source
        override val sink: BufferedSink get() = connection.sink
        override val remote: SocketAddress get() = connection.remote
        override fun close() = connection.close()
    }
}
