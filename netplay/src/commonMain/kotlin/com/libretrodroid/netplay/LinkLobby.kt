package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import okio.Closeable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A paired link cable peer. [localId] 0 hosts (serial server / Netpacket host), 1 joins. */
class LinkPeer(
    val connection: TcpConnection,
    val localId: Int,
    val peerName: String,
    /** The peer's LAN address, for cores that open their own link socket. */
    val peerHost: String,
)

/**
 * Pairs two devices running the same game in link mode on the LAN. Both advertise; the one that
 * started later connects to the earlier one, so exactly one TCP connection is made. The hello
 * carries [linkKey] (ROM, core and core version): a mismatch is rejected before any core runs.
 * [onPaired] runs once, on a background thread, with the connection now owned by the caller.
 */
class LinkLobby(
    private val linkKey: String,
    private val deviceName: String,
    private val discovery: LanDiscovery,
    private val onPaired: (LinkPeer) -> Unit,
) : Closeable {
    private val sessionId = Uuid.random().toString()
    private val startedAt = Clock.System.now().toEpochMilliseconds()
    private val paired = atomic(false)
    private val closed = atomic(false)
    @kotlin.concurrent.Volatile private var server: TcpServer? = null

    val gameKey: String get() = KEY_PREFIX + linkKey

    fun start() {
        val socket = TcpServer.bind(TCP_PORT)
        server = socket
        startThread("link-accept") {
            while (!closed.value) {
                val client = socket.accept() ?: break
                startThread("link-hello") { answer(client) }
            }
        }
        discovery.advertise(LanGame(sessionId, gameKey, deviceName, SocketAddress("127.0.0.1", socket.localPort), startedAt))
        discovery.discover { game ->
            val hostedFirst = game.startedAt < startedAt || (game.startedAt == startedAt && game.sessionId < sessionId)
            if (game.gameKey == gameKey && game.sessionId != sessionId && hostedFirst && !paired.value) {
                startThread("link-join") { join(game) }
            }
        }
    }

    private fun answer(client: TcpConnection) {
        try {
            val name = readHello(client)
            val accepted = name != null && !closed.value && paired.compareAndSet(false, true)
            client.sink.writeByte(if (accepted) ACCEPT else REJECT)
            client.sink.writeUtf8Line(deviceName)
            client.sink.flush()
            if (!accepted) return client.close()
            finish(LinkPeer(client, 0, name!!, client.remote.host.removePrefix("::ffff:")))
        } catch (e: Exception) {
            netplayLog("link hello failed: ${e.message}")
            runCatching { client.close() }
        }
    }

    private fun join(game: LanGame) {
        if (paired.value || closed.value) return
        val connection = runCatching { TcpConnection.connect(game.address, CONNECT_TIMEOUT_MS) }
            .onFailure { netplayLog("link connect failed: ${it.message}") }.getOrNull() ?: return
        try {
            connection.sink.writeInt(MAGIC)
            connection.sink.writeByte(VERSION)
            connection.sink.writeUtf8Line(gameKey)
            connection.sink.writeUtf8Line(deviceName)
            connection.sink.flush()
            val accepted = connection.source.readByte().toInt() == ACCEPT
            val name = connection.source.readUtf8LineStrict(MAX_LINE)
            if (!accepted || closed.value || !paired.compareAndSet(false, true)) return connection.close()
            finish(LinkPeer(connection, 1, name, game.address.host))
        } catch (e: Exception) {
            netplayLog("link join failed: ${e.message}")
            runCatching { connection.close() }
        }
    }

    private fun readHello(client: TcpConnection): String? {
        if (client.source.readInt() != MAGIC || client.source.readByte().toInt() != VERSION) return null
        val key = client.source.readUtf8LineStrict(MAX_LINE)
        val name = client.source.readUtf8LineStrict(MAX_LINE)
        return name.takeIf { key == gameKey }
    }

    private fun finish(peer: LinkPeer) {
        stopListening()
        onPaired(peer)
    }

    private fun stopListening() {
        runCatching { discovery.close() }
        runCatching { server?.close() }
        server = null
    }

    /** Stops looking for a peer. A connection already handed to [onPaired] belongs to the caller. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopListening()
    }

    private fun okio.BufferedSink.writeUtf8Line(text: String) {
        writeUtf8(text.replace('\n', ' '))
        writeByte('\n'.code)
    }

    companion object {
        const val KEY_PREFIX = "link|"
        private const val TCP_PORT = 55437
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val MAGIC = 0x4C494E4B // "LINK"
        private const val VERSION = 1
        private const val ACCEPT = 1
        private const val REJECT = 0
        private const val MAX_LINE = 512L
    }
}
