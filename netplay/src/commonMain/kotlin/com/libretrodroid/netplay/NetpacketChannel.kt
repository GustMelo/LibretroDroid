package com.libretrodroid.netplay

import okio.BufferedSink
import okio.BufferedSource

/**
 * Ordered transport for opaque core packets, shared by Android and iOS.
 * One writer and one reader may run concurrently; each direction has one owner.
 * The session authenticates/negotiates the peer before constructing this channel.
 * Incoming storage is reused and is valid only until the next read.
 */
class NetpacketChannel(
    private val source: BufferedSource,
    private val sink: BufferedSink,
) {
    private val incoming = ByteArray(MAX_PAYLOAD)
    var receivedSize: Int = 0
        private set
    var receivedFlags: Int = 0
        private set
    var receivedTarget: Int = 0
        private set

    fun send(flags: Int, data: ByteArray, size: Int, target: Int) {
        require(flags and ALLOWED_FLAGS.inv() == 0)
        require(flags and 3 != 3) { "Reliable packets cannot be unsequenced" }
        require(size in 0..MAX_PAYLOAD && size <= data.size)
        require(target in 0..MAX_CLIENT || target == BROADCAST)
        if (size == 0) {
            if (flags and FLUSH != 0) sink.flush()
            return
        }
        sink.writeIntLe(size)
        sink.writeByte(flags)
        sink.writeShortLe(target)
        sink.write(data, 0, size)
        // TCP already preserves reliable order. Do not retain a frame waiting for
        // another core callback: either side may be waiting for this packet.
        sink.flush()
    }

    /** Reads one complete packet or throws on EOF/malformed input. Never calls the core. */
    fun read(): ByteArray {
        receivedSize = 0
        val size = source.readIntLe()
        require(size in 1..MAX_PAYLOAD) { "Invalid Netpacket payload length" }
        val flags = source.readByte().toInt() and 255
        val target = source.readShortLe().toInt() and 65535
        require(flags and ALLOWED_FLAGS.inv() == 0 && flags and 3 != 3)
        require(target in 0..MAX_CLIENT || target == BROADCAST)
        var offset = 0
        while (offset < size) {
            val count = source.read(incoming, offset, size - offset)
            check(count > 0) { "Truncated Netpacket payload" }
            offset += count
        }
        receivedFlags = flags
        receivedTarget = target
        receivedSize = size
        return incoming
    }

    companion object {
        const val MAX_PAYLOAD = 65536
        const val MAX_CLIENT = 3
        const val BROADCAST = 65535
        const val FLUSH = 4
        private const val ALLOWED_FLAGS = 7
    }
}
