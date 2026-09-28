package com.libretrodroid.netplay

import okio.Buffer

/** Wire protocol for emulated serial/link-cable transfers.
 *
 * It deliberately carries only serial events; video, audio and controller input
 * remain local to each emulator instance. This keeps the hot path tiny and
 * deterministic on LAN and on a loopback connection.
 */
object LinkCableProtocol {
    const val VERSION = 1
    const val MAX_PACKET = 256

    sealed interface Packet {
        data class Hello(val gameKey: String, val system: Int, val players: Int) : Packet
        data class Transfer(val sequence: Int, val cycle: Long, val value: Int) : Packet
        data class TransferReply(val sequence: Int, val value: Int) : Packet
        data class Reset(val sequence: Int) : Packet
        data object Bye : Packet
    }

    private const val HELLO = 1
    private const val TRANSFER = 2
    private const val REPLY = 3
    private const val RESET = 4
    private const val BYE = 5

    fun encode(packet: Packet): ByteArray = Buffer().apply {
        when (packet) {
            is Packet.Hello -> {
                writeByte(HELLO); writeByte(VERSION); writeByte(packet.system); writeByte(packet.players)
                val key = packet.gameKey.encodeToByteArray(); require(key.size <= 128)
                writeByte(key.size); write(key)
            }
            is Packet.Transfer -> { writeByte(TRANSFER); writeIntLe(packet.sequence); writeLongLe(packet.cycle); writeByte(packet.value) }
            is Packet.TransferReply -> { writeByte(REPLY); writeIntLe(packet.sequence); writeByte(packet.value) }
            is Packet.Reset -> { writeByte(RESET); writeIntLe(packet.sequence) }
            Packet.Bye -> writeByte(BYE)
        }
    }.readByteArray()

    fun decode(bytes: ByteArray, length: Int = bytes.size): Packet? = runCatching {
        require(length in 1..MAX_PACKET)
        val b = Buffer().write(bytes, 0, length)
        when (b.readByte().toInt()) {
            HELLO -> {
                require(b.readByte().toInt() == VERSION)
                val system = b.readByte().toInt() and 0xff
                val players = b.readByte().toInt() and 0xff
                val size = b.readByte().toInt() and 0xff
                Packet.Hello(b.readUtf8(size.toLong()), system, players)
            }
            TRANSFER -> Packet.Transfer(b.readIntLe(), b.readLongLe(), b.readByte().toInt() and 0xff)
            REPLY -> Packet.TransferReply(b.readIntLe(), b.readByte().toInt() and 0xff)
            RESET -> Packet.Reset(b.readIntLe())
            BYE -> Packet.Bye
            else -> null
        }
    }.getOrNull()
}
