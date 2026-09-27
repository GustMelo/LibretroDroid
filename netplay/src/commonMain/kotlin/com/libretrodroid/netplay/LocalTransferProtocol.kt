package com.libretrodroid.netplay

import okio.Buffer
import okio.ByteString

/** Small, length-delimited manifest exchanged before copying local game assets. */
data class TransferManifest(
    val gameKey: String,
    val files: List<TransferFile>,
    val protocol: Int = LocalTransferProtocol.VERSION,
) {
    init {
        require(gameKey.isNotBlank() && gameKey.length <= LocalTransferProtocol.MAX_TEXT)
        require(files.size <= LocalTransferProtocol.MAX_FILES)
    }
}

data class TransferFile(
    val relativePath: String,
    val size: Long,
    val sha256: ByteString,
    val required: Boolean,
) {
    init {
        require(relativePath.isNotBlank() && relativePath.length <= LocalTransferProtocol.MAX_TEXT)
        require(size >= 0L && sha256.size == LocalTransferProtocol.SHA256_BYTES)
    }
}

object LocalTransferProtocol {
    const val VERSION = 1
    const val MAX_FILES = 256
    const val MAX_TEXT = 512
    const val SHA256_BYTES = 32
    private const val MAX_MANIFEST = 64 * 1024

    fun encode(manifest: TransferManifest): ByteArray {
        val body = Buffer().apply {
            writeIntLe(manifest.protocol)
            writeUtf8Field(manifest.gameKey)
            writeIntLe(manifest.files.size)
            manifest.files.forEach {
                writeUtf8Field(it.relativePath)
                writeLongLe(it.size)
                writeByte(if (it.required) 1 else 0)
                write(it.sha256)
            }
        }
        require(body.size <= MAX_MANIFEST)
        return Buffer().apply { writeIntLe(body.size.toInt()); writeAll(body) }.readByteArray()
    }

    fun decode(bytes: ByteArray): TransferManifest {
        val source = Buffer().write(bytes)
        val size = source.readIntLe()
        require(size in 1..MAX_MANIFEST && source.size >= size)
        val body = Buffer().also { source.read(it, size.toLong()) }
        val protocol = body.readIntLe()
        require(protocol == VERSION)
        val gameKey = body.readUtf8Field()
        val count = body.readIntLe()
        require(count in 0..LocalTransferProtocol.MAX_FILES)
        val files = List(count) {
            val path = body.readUtf8Field()
            val length = body.readLongLe()
            val required = body.readByte().toInt() != 0
            TransferFile(path, length, body.readByteString(LocalTransferProtocol.SHA256_BYTES.toLong()), required)
        }
        require(body.exhausted())
        return TransferManifest(gameKey, files, protocol)
    }

    private fun Buffer.writeUtf8Field(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= LocalTransferProtocol.MAX_TEXT)
        writeShortLe(bytes.size)
        write(bytes)
    }

    private fun Buffer.readUtf8Field(): String = readUtf8((readShortLe().toInt() and 0xffff).toLong()).also {
        require(it.length <= LocalTransferProtocol.MAX_TEXT)
    }
}

sealed interface TransferDecision {
    data object Accept : TransferDecision
    data class Reject(val reason: String) : TransferDecision
}

/** Framed asset transfer. The caller owns consent, integrity checks and connection lifetime. */
class LocalTransferChannel(private val connection: TcpConnection) : okio.Closeable {
    fun sendManifest(manifest: TransferManifest) {
        connection.sink.write(LocalTransferProtocol.encode(manifest)).flush()
    }

    fun readManifest(): TransferManifest { val frame = readFrame(); return LocalTransferProtocol.decode(Buffer().apply { writeIntLe(frame.size); write(frame) }.readByteArray()) }

    fun sendDecision(decision: TransferDecision) {
        val body = Buffer().apply {
            writeByte(if (decision is TransferDecision.Accept) 1 else 0)
            if (decision is TransferDecision.Reject) writeUtf8Field(decision.reason)
        }
        writeFrame(body.readByteArray())
    }

    fun readDecision(): TransferDecision {
        val body = Buffer().write(readFrame())
        return if (body.readByte().toInt() == 1) TransferDecision.Accept
        else TransferDecision.Reject(body.readUtf8Field())
    }

    fun sendFile(file: TransferFile, read: (offset: Long, maxBytes: Int) -> ByteArray): Long {
        var offset = 0L
        while (offset < file.size) {
            val chunk = read(offset, CHUNK_SIZE).also { require(it.isNotEmpty() && it.size <= CHUNK_SIZE && it.size.toLong() <= file.size - offset) }
            val body = Buffer().apply { writeByte(2); writeLongLe(offset); writeIntLe(chunk.size); write(chunk) }
            writeFrame(body.readByteArray())
            offset += chunk.size
        }
        writeFrame(Buffer().apply { writeByte(3); writeLongLe(file.size) }.readByteArray())
        return offset
    }

    fun receiveFile(file: TransferFile, write: (offset: Long, bytes: ByteArray) -> Unit): Long {
        var received = 0L
        while (true) {
            val body = Buffer().write(readFrame())
            when (body.readByte().toInt()) {
                2 -> {
                    val offset = body.readLongLe(); val size = body.readIntLe()
                    require(offset == received && size in 1..CHUNK_SIZE && body.size == size.toLong() && size.toLong() <= file.size - received)
                    write(offset, body.readByteArray(size.toLong())); received += size
                }
                3 -> { require(body.readLongLe() == received && received == file.size && body.exhausted()); return received }
                else -> error("unexpected transfer frame")
            }
        }
    }

    override fun close() = connection.close()

    private fun writeFrame(bytes: ByteArray) {
        require(bytes.size <= MAX_FRAME)
        connection.sink.writeIntLe(bytes.size).write(bytes).flush()
    }

    private fun readFrame(): ByteArray {
        val size = connection.source.readIntLe()
        require(size in 1..MAX_FRAME)
        return connection.source.readByteArray(size.toLong())
    }

    private companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val MAX_FRAME = CHUNK_SIZE + 32
        fun Buffer.writeUtf8Field(value: String) { val b = value.encodeToByteArray(); require(b.size <= LocalTransferProtocol.MAX_TEXT); writeShortLe(b.size); write(b) }
        fun Buffer.readUtf8Field(): String = readUtf8((readShortLe().toInt() and 0xffff).toLong())
    }
}
