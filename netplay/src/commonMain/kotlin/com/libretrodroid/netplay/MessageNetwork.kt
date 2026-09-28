package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * Netplay over message links, one per remote player, in a star around the host: WebRTC data channels across
 * the internet. The platform owns the links (connection setup, ICE, the channels) and plugs each in with
 * [attach] once both of its channels are open; this side turns them into what [NetplaySession] expects.
 * A link's address is `SocketAddress("link", id)`: the host accepts one stream per attached link, a player
 * who joins connects to its only link.
 */
class MessageNetwork : NetplayNetwork {
    /** What the platform supplies for one link. Both sends must not block; false means the link is gone. */
    interface Link {
        /** Ordered and reliable: control messages and states, in chunks of at most [MAX_MESSAGE]. */
        fun sendReliable(bytes: ByteArray): Boolean

        /** Unordered, never retransmitted: inputs and pings. */
        fun sendDatagram(bytes: ByteArray): Boolean

        fun close()
    }

    /** What the platform calls for one attached link: every message it receives, and its end. */
    inner class Ends internal constructor(private val id: Int, internal val link: Link) {
        internal val stream = LinkStream(id, link)

        fun reliable(bytes: ByteArray) = stream.deliver(bytes)

        fun datagram(bytes: ByteArray) {
            if (bytes.size <= MAX_DATAGRAM) incoming.trySend(bytes to id)
        }

        /** The link closed or failed: its stream ends, so the session sees the player leave. */
        fun closed() {
            synchronized(lock) { links.remove(id) }
            stream.end()
        }
    }

    private val lock = SynchronizedObject()
    private val links = HashMap<Int, Ends>()
    private val nextId = atomic(0)
    private val incoming = Channel<Pair<ByteArray, Int>>(DATAGRAM_QUEUE)
    private val accepted = Channel<StreamConnection>(Channel.UNLIMITED)
    private val closed = atomic(false)

    fun attach(link: Link): Ends {
        val ends = Ends(nextId.getAndIncrement(), link)
        synchronized(lock) { links[ends.stream.id] = ends }
        accepted.trySend(ends.stream)
        return ends
    }

    override val datagrams: NetplayDatagrams = object : NetplayDatagrams {
        override val localPort: Int get() = 0

        override fun send(bytes: ByteArray, length: Int, to: SocketAddress) {
            val ends = synchronized(lock) { links[to.port] } ?: return
            ends.link.sendDatagram(bytes.copyOf(length))
        }

        override fun receive(buffer: ByteArray): Received? {
            val (bytes, from) = runBlocking { incoming.receiveCatching().getOrNull() } ?: return null
            val length = minOf(bytes.size, buffer.size)
            bytes.copyInto(buffer, 0, 0, length)
            return Received(length, address(from))
        }

        override fun close() = Unit
    }

    override fun listen(): StreamServer = object : StreamServer {
        override val localPort: Int get() = 0
        override fun accept(): StreamConnection? = runBlocking { accepted.receiveCatching().getOrNull() }
        override fun close() = Unit
    }

    /** A joining player has one link, to the host: its stream, whatever [address] says. */
    override fun connect(address: SocketAddress, timeoutMillis: Int): StreamConnection {
        val stream = runBlocking { accepted.receiveCatching().getOrNull() } ?: error("no link to the host")
        return stream
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        incoming.close()
        accepted.close()
        val all = synchronized(lock) { links.values.toList().also { links.clear() } }
        all.forEach { it.link.close(); it.stream.end() }
    }

    internal class LinkStream(val id: Int, private val link: Link) : StreamConnection {
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

        fun deliver(bytes: ByteArray) {
            chunks.trySend(bytes)
        }

        fun end() {
            chunks.close()
        }

        override val remote: SocketAddress = address(id)

        override val source: BufferedSource = object : Source {
            private var pending: Buffer = Buffer()
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (pending.size == 0L) {
                    val next = runBlocking { chunks.receiveCatching().getOrNull() } ?: return -1
                    pending.write(next)
                }
                return pending.read(sink, minOf(byteCount, pending.size))
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = end()
        }.buffer()

        override val sink: BufferedSink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                var left = byteCount
                while (left > 0) {
                    val size = minOf(left, MAX_MESSAGE.toLong())
                    check(link.sendReliable(source.readByteArray(size))) { "link closed" }
                    left -= size
                }
            }
            override fun flush() = Unit
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = link.close()
        }.buffer()

        override fun close() {
            link.close()
            end()
        }
    }

    companion object {
        /** Keeps every message well under what data channels deliver everywhere (Safari's 64 KiB). */
        const val MAX_MESSAGE = 16 * 1024
        const val MAX_DATAGRAM = 4096
        private const val DATAGRAM_QUEUE = 256
        const val HOST = "link"

        fun address(id: Int) = SocketAddress(HOST, id)
    }
}
