package com.libretrodroid.netplay

import okio.Buffer
import okio.ByteString

object NetplayProtocol {

    const val VERSION = 8
    const val REDUNDANCY = 8
    const val MAX_PLAYERS = 4
    const val MAX_INPUT_DELAY = 8

    const val RESEND_WINDOW = 2 * MAX_INPUT_DELAY + 2
}

sealed interface Datagram {

    data class Input(val port: Int, val lastFrame: Int, val buttons: List<Int>, val session: Int = 0) : Datagram {
        val firstFrame: Int get() = lastFrame - buttons.size + 1
    }

    data class StateHash(val port: Int, val frame: Int, val hash: Long, val session: Int = 0) : Datagram

    data class Performance(val port: Int, val frame: Int, val counters: Long, val session: Int) : Datagram

    data class Hello(val token: Int) : Datagram

    data class Ping(val nonce: Int, val sentAtNanos: Long) : Datagram
    data class Pong(val nonce: Int, val sentAtNanos: Long) : Datagram

    companion object {
        private const val INPUT: Byte = 1
        private const val HASH: Byte = 2
        private const val PING: Byte = 3
        private const val PONG: Byte = 4
        private const val HELLO: Byte = 5
        private const val PERFORMANCE: Byte = 6

        fun encode(datagram: Datagram): ByteArray = Buffer().apply {
            when (datagram) {
                is Input -> {
                    writeByte(INPUT.toInt()); writeByte(datagram.port); writeByte(datagram.session); writeIntLe(datagram.lastFrame)
                    writeByte(datagram.buttons.size)
                    datagram.buttons.forEach { writeShortLe(it) }
                }
                is StateHash -> {
                    writeByte(HASH.toInt()); writeByte(datagram.port); writeByte(datagram.session)
                    writeIntLe(datagram.frame); writeLongLe(datagram.hash)
                }
                is Ping -> { writeByte(PING.toInt()); writeIntLe(datagram.nonce); writeLongLe(datagram.sentAtNanos) }
                is Pong -> { writeByte(PONG.toInt()); writeIntLe(datagram.nonce); writeLongLe(datagram.sentAtNanos) }
                is Performance -> {
                    writeByte(PERFORMANCE.toInt()); writeByte(datagram.port); writeByte(datagram.session)
                    writeIntLe(datagram.frame); writeLongLe(datagram.counters)
                }
                is Hello -> { writeByte(HELLO.toInt()); writeIntLe(datagram.token) }
            }
        }.readByteArray()

        fun decode(bytes: ByteArray, length: Int = bytes.size): Datagram? = runCatching {
            val buffer = Buffer().write(bytes, 0, length)
            when (buffer.readByte()) {
                INPUT -> {
                    val port = buffer.readByte().toInt() and 0xFF
                    val session = buffer.readByte().toInt() and 0xFF
                    val last = buffer.readIntLe()
                    val count = buffer.readByte().toInt() and 0xFF
                    require(port < NetplayProtocol.MAX_PLAYERS && count in 1..64)
                    Input(port, last, List(count) { buffer.readShortLe().toInt() and 0xFFFF }, session)
                }
                HASH -> {
                    val port = buffer.readByte().toInt() and 0xFF
                    val session = buffer.readByte().toInt() and 0xFF
                    StateHash(port, buffer.readIntLe(), buffer.readLongLe(), session)
                }
                PERFORMANCE -> {
                    val port = buffer.readByte().toInt() and 0xFF
                    val session = buffer.readByte().toInt() and 0xFF
                    require(port < NetplayProtocol.MAX_PLAYERS)
                    Performance(port, buffer.readIntLe(), buffer.readLongLe(), session)
                }
                PING -> Ping(buffer.readIntLe(), buffer.readLongLe())
                PONG -> Pong(buffer.readIntLe(), buffer.readLongLe())
                HELLO -> Hello(buffer.readIntLe())
                else -> null
            }
        }.getOrNull()
    }
}

sealed interface ControlMessage {

    data class Hello(
        val version: Int,
        val gameKey: String,
        val name: String,
        val datagramPort: Int,
        val recompiler: Boolean = false,
        /** Linked consoles: the joining player's own save, for the console the host gives them. */
        val linkSave: ByteString = ByteString.EMPTY,
    ) : ControlMessage

    data class Welcome(val port: Int, val datagramPort: Int, val hostName: String, val token: Int) : ControlMessage

    data class Reject(val reason: String) : ControlMessage

    data class Start(
        val port: Int,
        val players: Int,
        val inputDelay: Int,
        val state: ByteString,
        val session: Int = 0,
        val recompiler: Boolean = false,

        val saveRam: ByteString = ByteString.EMPTY,
    ) : ControlMessage

    data class Roster(val names: List<String>) : ControlMessage

    data object Bye : ControlMessage

    data object RestartRequest : ControlMessage

    companion object {
        private const val HELLO = 1
        private const val WELCOME = 2
        private const val REJECT = 3
        private const val START = 4
        private const val ROSTER = 5
        private const val BYE = 6
        private const val RESTART_REQUEST = 7

        const val MAX_SIZE = 16 * 1024 * 1024

        fun encode(message: ControlMessage): ByteArray {
            val body = Buffer().apply {
                when (message) {
                    is Hello -> {
                        writeByte(HELLO); writeIntLe(message.version); writeUtf8Field(message.gameKey); writeUtf8Field(message.name)
                        writeIntLe(message.datagramPort); writeByte(if (message.recompiler) 1 else 0)
                        writeIntLe(message.linkSave.size); write(message.linkSave)
                    }
                    is Welcome -> { writeByte(WELCOME); writeByte(message.port); writeIntLe(message.datagramPort); writeUtf8Field(message.hostName); writeIntLe(message.token) }
                    is Reject -> { writeByte(REJECT); writeUtf8Field(message.reason) }
                    is Start -> {
                        writeByte(START); writeByte(message.port); writeByte(message.players); writeByte(message.inputDelay)
                        writeByte(message.session); writeByte(if (message.recompiler) 1 else 0)
                        writeIntLe(message.state.size); write(message.state)
                        writeIntLe(message.saveRam.size); write(message.saveRam)
                    }
                    is Roster -> { writeByte(ROSTER); writeByte(message.names.size); message.names.forEach { writeUtf8Field(it) } }
                    Bye -> writeByte(BYE)
                    RestartRequest -> writeByte(RESTART_REQUEST)
                }
            }
            return Buffer().writeIntLe(body.size.toInt()).apply { writeAll(body) }.readByteArray()
        }

        fun read(source: okio.BufferedSource): ControlMessage {
            val size = source.readIntLe()
            require(size in 1..MAX_SIZE) { "control message with invalid size: $size" }
            source.require(size.toLong())
            val body = Buffer().also { source.read(it, size.toLong()) }
            return when (val type = body.readByte().toInt()) {
                HELLO -> {
                    val version = body.readIntLe()

                    if (version != NetplayProtocol.VERSION) Hello(version, "", "", 0)
                    else Hello(
                        version, body.readUtf8Field(), body.readUtf8Field(), body.readIntLe(), body.readByte() != 0.toByte(),
                        body.readByteString(body.readIntLe().toLong()),
                    )
                }
                WELCOME -> Welcome(body.readByte().toInt() and 0xFF, body.readIntLe(), body.readUtf8Field(), body.readIntLe())
                REJECT -> Reject(body.readUtf8Field())
                START -> {
                    val port = body.readByte().toInt() and 0xFF
                    val players = body.readByte().toInt() and 0xFF
                    val delay = body.readByte().toInt() and 0xFF
                    val session = body.readByte().toInt() and 0xFF
                    val recompiler = body.readByte() != 0.toByte()
                    val state = body.readByteString(body.readIntLe().toLong())
                    val saveRam = body.readByteString(body.readIntLe().toLong())
                    Start(port, players, delay, state, session, recompiler, saveRam)
                }
                ROSTER -> Roster(List(body.readByte().toInt() and 0xFF) { body.readUtf8Field() })
                BYE -> Bye
                RESTART_REQUEST -> RestartRequest
                else -> error("unknown message type: $type")
            }
        }

        private fun Buffer.writeUtf8Field(value: String) {
            val bytes = value.encodeToByteArray()
            writeShortLe(bytes.size)
            write(bytes)
        }

        private fun Buffer.readUtf8Field(): String = readUtf8((readShortLe().toInt() and 0xFFFF).toLong())
    }
}

class InputHistory(
    private val port: Int,
    private val session: Int = 0,
    private val redundancy: Int = NetplayProtocol.REDUNDANCY,
    private val window: Int = NetplayProtocol.RESEND_WINDOW,
) {
    private val recent = IntArray(window)
    private var count = 0
    private var cursor = 0
    private var lastFrame = -1

    init {
        require(window in 1..64 && redundancy in 1..window)
    }

    private fun append(frame: Int, value: Int) {
        if (lastFrame >= 0 && frame != lastFrame + 1) {
            count = 0
            cursor = 0
        }
        recent[cursor] = value
        cursor = (cursor + 1) % window
        count = minOf(count + 1, window)
        lastFrame = frame
    }

    private fun value(index: Int, size: Int) = recent[(cursor - size + index + window) % window]

    fun record(frame: Int, value: Int): Datagram.Input {
        append(frame, value)
        val size = minOf(count, redundancy)
        return Datagram.Input(port, frame, List(size) { value(it, size) }, session)
    }

    fun resend(): Datagram.Input? = if (count == 0) null
        else Datagram.Input(port, lastFrame, List(count) { value(it, count) }, session)

    fun recordEncoded(frame: Int, buttons: Int, buffer: ByteArray): Int {
        append(frame, buttons)
        return encodeInto(buffer, minOf(count, redundancy))
    }

    fun resendEncoded(buffer: ByteArray): Int = if (count == 0) 0 else encodeInto(buffer, count)

    private fun encodeInto(buffer: ByteArray, size: Int): Int {
        require(buffer.size >= 8 + size * 2)
        buffer[0] = 1
        buffer[1] = port.toByte()
        buffer[2] = session.toByte()
        repeat(4) { buffer[3 + it] = (lastFrame ushr (it * 8)).toByte() }
        buffer[7] = size.toByte()
        repeat(size) {
            val buttons = value(it, size)
            buffer[8 + it * 2] = buttons.toByte()
            buffer[9 + it * 2] = (buttons ushr 8).toByte()
        }
        return 8 + size * 2
    }
}

fun inputDelayFor(
    roundTripMillis: Double,
    rollback: Boolean = false,
    minDelay: Int = 0,
    frameMillis: Double = 1000.0 / 60,
    jitterMillis: Double = 0.0,
): Int {
    // Reserve one jitter budget on top of one-way propagation. This avoids
    // oscillating between stalls and rollbacks on otherwise healthy Wi-Fi.
    val oneWay = (roundTripMillis / 2 + jitterMillis) / frameMillis
    return if (rollback) kotlin.math.floor(oneWay).toInt().coerceIn(minDelay, maxOf(minDelay, ROLLBACK_MAX_DELAY))
    else (kotlin.math.ceil(oneWay).toInt() + 1).coerceIn(2, NetplayProtocol.MAX_INPUT_DELAY)
}

private const val ROLLBACK_MAX_DELAY = 4
