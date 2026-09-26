package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Closeable
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import platform.Foundation.NSLog
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_NUMERICHOST
import platform.posix.IPPROTO_IPV6
import platform.posix.IPPROTO_TCP
import platform.posix.IPV6_V6ONLY
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.NI_NUMERICSERV
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror

private const val IPV6_TCLASS = 36
private const val DSCP_EF = 0xB8
private const val UDP_BUFFER_BYTES = 256 * 1024

actual class UdpSocket private constructor(private val fd: Int, actual val localPort: Int) : Closeable {
    private val cache = AddressCache()
    private val closed = atomic(false)

    actual fun send(bytes: ByteArray, length: Int, to: SocketAddress) {
        val address = cache.resolve(to)
        val sent = bytes.usePinned { pinned ->
            sendto(fd, pinned.addressOf(0), length.convert(), 0, address.pointer, address.length)
        }
        if (sent < 0) throw IOException("sendto: ${errorText()}")
    }

    actual fun receive(buffer: ByteArray): Received? = memScoped {
        val from = alloc<sockaddr_storage>()
        val fromLength = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
        val read = buffer.usePinned { pinned ->
            recvfrom(fd, pinned.addressOf(0), buffer.size.convert(), 0, from.ptr.reinterpret(), fromLength.ptr)
        }
        if (closed.value) return null
        if (read < 0) throw IOException("recvfrom: ${errorText()}")
        Received(read.toInt(), from.ptr.reinterpret<sockaddr>().toSocketAddress(fromLength.value))
    }

    actual override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { send(ByteArray(1), 1, SocketAddress("::1", localPort)) }
        close(fd)
    }

    actual companion object {
        actual fun bind(preferredPort: Int): UdpSocket {
            val fd = dualStackSocket(SOCK_DGRAM)
            setInt(fd, IPPROTO_IPV6, IPV6_TCLASS, DSCP_EF)
            setInt(fd, SOL_SOCKET, platform.posix.SO_RCVBUF, UDP_BUFFER_BYTES)
            setInt(fd, SOL_SOCKET, platform.posix.SO_SNDBUF, UDP_BUFFER_BYTES)
            val port = bindAny(fd, preferredPort)
            return UdpSocket(fd, port)
        }
    }
}

actual class TcpConnection(private val fd: Int, actual val remote: SocketAddress) : Closeable {
    private val closed = atomic(false)
    actual val source: BufferedSource = FdSource(fd).buffer()
    actual val sink: BufferedSink = FdSink(fd).buffer()

    actual override fun close() {
        // Session teardown and the receive loop may both close the connection.
        // A reused POSIX descriptor must never be closed a second time.
        if (!closed.compareAndSet(false, true)) return
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }

    actual companion object {
        actual fun connect(to: SocketAddress, timeoutMillis: Int): TcpConnection = memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
            }
            val result = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
            val status = getaddrinfo(to.host, to.port.toString(), hints.ptr, result.ptr)
            if (status != 0) throw IOException("could not resolve ${to.host}")
            try {
                var info = result.value
                while (info != null) {
                    val entry = info.pointed
                    val fd = socket(entry.ai_family, SOCK_STREAM, IPPROTO_TCP)
                    if (fd >= 0) {
                        setInt(fd, SOL_SOCKET, SO_NOSIGPIPE, 1)
                        setInt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
                        setTimeout(fd, timeoutMillis)
                        if (connect(fd, entry.ai_addr, entry.ai_addrlen) == 0) {
                            setTimeout(fd, 0)
                            val remote = entry.ai_addr!!.toSocketAddress(entry.ai_addrlen)
                            return TcpConnection(fd, remote)
                        }
                        close(fd)
                    }
                    info = entry.ai_next
                }
            } finally {
                freeaddrinfo(result.value)
            }
            throw IOException("could not connect to ${to.host}:${to.port}: ${errorText()}")
        }
    }
}

actual class TcpServer private constructor(private val fd: Int, actual val localPort: Int) : Closeable {
    private val closed = atomic(false)

    actual fun accept(): TcpConnection? = memScoped {
        val from = alloc<sockaddr_storage>()
        val fromLength = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
        val client = accept(fd, from.ptr.reinterpret(), fromLength.ptr)
        if (closed.value) {
            if (client >= 0) close(client)
            return null
        }
        if (client < 0) return null
        setInt(client, SOL_SOCKET, SO_NOSIGPIPE, 1)
        setInt(client, IPPROTO_TCP, TCP_NODELAY, 1)
        TcpConnection(client, from.ptr.reinterpret<sockaddr>().toSocketAddress(fromLength.value))
    }

    actual override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { TcpConnection.connect(SocketAddress("::1", localPort), 500).close() }
        close(fd)
    }

    actual companion object {
        actual fun bind(preferredPort: Int): TcpServer {
            val fd = dualStackSocket(SOCK_STREAM)
            setInt(fd, SOL_SOCKET, SO_REUSEADDR, 1)
            val port = bindAny(fd, preferredPort)
            if (listen(fd, 4) != 0) throw IOException("listen: ${errorText()}")
            return TcpServer(fd, port)
        }
    }
}

internal actual fun isLocalAddress(host: String): Boolean {
    val normalized = host.removePrefix("::ffff:")
    if (normalized == "127.0.0.1" || normalized == "::1" || normalized == "0.0.0.0" || normalized == "::") return true
    return memScoped {
        val list = alloc<kotlinx.cinterop.CPointerVar<ifaddrs>>()
        if (getifaddrs(list.ptr) != 0) return false
        try {
            var entry = list.value
            while (entry != null) {
                val address = entry.pointed.ifa_addr
                val family = address?.pointed?.sa_family?.toInt()
                if (address != null && (family == AF_INET || family == AF_INET6)) {
                    val length = if (family == AF_INET) sizeOf<platform.posix.sockaddr_in>() else sizeOf<sockaddr_in6>()
                    if (address.toSocketAddress(length.convert()).host.substringBefore('%') == normalized.substringBefore('%')) return true
                }
                entry = entry.pointed.ifa_next
            }
            false
        } finally {
            freeifaddrs(list.value)
        }
    }
}

internal actual fun netplayLog(message: String) {
    NSLog("Netplay: " + message.replace("%", "%%"))
}

private fun dualStackSocket(type: Int): Int {
    val fd = socket(AF_INET6, type, 0)
    if (fd < 0) throw IOException("socket: ${errorText()}")
    setInt(fd, IPPROTO_IPV6, IPV6_V6ONLY, 0)
    setInt(fd, SOL_SOCKET, SO_NOSIGPIPE, 1)
    return fd
}

private fun bindAny(fd: Int, preferredPort: Int): Int = memScoped {
    fun tryBind(port: Int): Boolean {
        val address = alloc<sockaddr_in6>().apply {
            sin6_len = sizeOf<sockaddr_in6>().convert()
            sin6_family = AF_INET6.convert()
            sin6_port = htons(port)
        }
        return bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert()) == 0
    }
    if (!tryBind(preferredPort) && !tryBind(0)) throw IOException("bind: ${errorText()}")
    val bound = alloc<sockaddr_in6>()
    val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in6>().convert() }
    getsockname(fd, bound.ptr.reinterpret(), length.ptr)
    ntohs(bound.sin6_port)
}

private fun setInt(fd: Int, level: Int, option: Int, value: Int) = memScoped {
    val v = alloc<IntVar>().apply { this.value = value }
    setsockopt(fd, level, option, v.ptr, sizeOf<IntVar>().convert())
}

private fun setTimeout(fd: Int, millis: Int) = memScoped {
    val timeout = alloc<platform.posix.timeval>().apply {
        tv_sec = (millis / 1000).convert()
        tv_usec = ((millis % 1000) * 1000).convert()
    }
    setsockopt(fd, SOL_SOCKET, platform.posix.SO_SNDTIMEO, timeout.ptr, sizeOf<platform.posix.timeval>().convert())
    setsockopt(fd, SOL_SOCKET, platform.posix.SO_RCVTIMEO, timeout.ptr, sizeOf<platform.posix.timeval>().convert())
}

private fun htons(port: Int): UShort = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
private fun ntohs(port: UShort): Int = port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }

private fun errorText(): String = strerror(errno)?.toKString() ?: "errno $errno"

private fun CPointer<sockaddr>.toSocketAddress(length: UInt): SocketAddress = memScoped {
    val host = allocArray<ByteVar>(NI_MAXHOST)
    val service = allocArray<ByteVar>(32)
    getnameinfo(this@toSocketAddress, length, host, NI_MAXHOST.convert(), service, 32u, NI_NUMERICHOST or NI_NUMERICSERV)
    SocketAddress(host.toKString().removePrefix("::ffff:"), service.toKString().toIntOrNull() ?: 0)
}

private class NativeAddress(val pointer: CPointer<sockaddr>, val length: UInt)

private class AddressCache {
    private val lock = SynchronizedObject()
    private val entries = HashMap<SocketAddress, NativeAddress>()

    fun resolve(address: SocketAddress): NativeAddress = synchronized(lock) {
        entries.getOrPut(address) { lookup(address) }
    }

    private fun lookup(address: SocketAddress): NativeAddress = memScoped {
        val host = if (address.host.contains(':')) address.host else "::ffff:${address.host}"
        val hints = alloc<addrinfo>().apply {
            ai_family = AF_INET6
            ai_socktype = SOCK_DGRAM
            ai_flags = AI_NUMERICHOST
        }
        val result = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
        if (getaddrinfo(host, address.port.toString(), hints.ptr, result.ptr) != 0) {
            throw IOException("invalid address: ${address.host}")
        }
        try {
            val info = result.value!!.pointed
            val copy = nativeHeap.allocArray<ByteVar>(info.ai_addrlen.toInt())
            platform.posix.memcpy(copy, info.ai_addr, info.ai_addrlen.convert())
            NativeAddress(copy.reinterpret(), info.ai_addrlen)
        } finally {
            freeaddrinfo(result.value)
        }
    }
}

private class FdSource(private val fd: Int) : Source {
    private val chunk = ByteArray(8192)

    override fun read(sink: Buffer, byteCount: Long): Long {
        val wanted = minOf(byteCount, chunk.size.toLong()).toInt()
        val read = chunk.usePinned { recv(fd, it.addressOf(0), wanted.convert(), 0) }
        if (read == 0L) return -1
        if (read < 0) throw IOException("recv: ${errorText()}")
        sink.write(chunk, 0, read.toInt())
        return read
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private class FdSink(private val fd: Int) : Sink {
    private val chunk = ByteArray(8192)

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val count = source.read(chunk, 0, minOf(remaining, chunk.size.toLong()).toInt())
            var offset = 0
            while (offset < count) {
                val sent = chunk.usePinned { send(fd, it.addressOf(offset), (count - offset).convert(), 0) }
                if (sent < 0) throw IOException("send: ${errorText()}")
                offset += sent.toInt()
            }
            remaining -= count
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}
