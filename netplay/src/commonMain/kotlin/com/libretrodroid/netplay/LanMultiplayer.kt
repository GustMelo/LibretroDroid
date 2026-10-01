package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import okio.Closeable
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class LanGame(
    val sessionId: String,
    val gameKey: String,
    val hostName: String,
    val address: SocketAddress,
    val startedAt: Long,
)

interface LanDiscovery : Closeable {
    fun advertise(game: LanGame)
    fun stopAdvertising()
    /** [onLost] gets the [LanGame.sessionId] of a game that is no longer announced. Both run on background threads. */
    fun discover(onLost: (String) -> Unit = {}, onFound: (LanGame) -> Unit)

    companion object {
        const val SERVICE_TYPE = "_libretrodroid._tcp"
        const val KEY_SESSION = "id"
        const val KEY_GAME = "game"
        const val KEY_NAME = "name"
        const val KEY_STARTED = "t"
    }
}

class LanMultiplayer(
    emulator: NetplayEmulator,
    private val gameKey: String,
    maxPlayers: Int,
    private val deviceName: String,
    private val discovery: LanDiscovery,
    private val keepRadioAwake: (Boolean) -> Unit = {},
    debugDelay: Int? = null,
    onStatus: (NetplayStatus) -> Unit,
) : Closeable {
    private val sessionId = Uuid.random().toString()
    private val startedAt = Clock.System.now().toEpochMilliseconds()
    private val joining = atomic(false)
    private var advertised: LanGame? = null

    private val session = NetplaySession(emulator, gameKey, deviceName, maxPlayers, debugDelay) { status ->
        keepRadioAwake(status is NetplayStatus.Playing)
        if (status is NetplayStatus.Ended && joining.compareAndSet(true, false)) {
            advertised?.let { runCatching { discovery.advertise(it) } }
        }
        onStatus(status)
    }

    val listener: NetplayListener get() = session

    fun start() {
        val port = session.host()
        val mine = LanGame(sessionId, gameKey, deviceName, SocketAddress("127.0.0.1", port), startedAt)
        advertised = mine
        discovery.advertise(mine)
        discovery.discover { game ->
            val sameGame = game.gameKey == gameKey && game.sessionId != sessionId && !session.isOwnServer(game)
            val hostedFirst = game.startedAt < startedAt || (game.startedAt == startedAt && game.sessionId < sessionId)
            if (sameGame && hostedFirst && session.isAlone && joining.compareAndSet(false, true)) {
                discovery.stopAdvertising()
                session.join(game)
            }
        }
    }

    fun restart() = session.restart()

    fun join(host: String, port: Int) {
        if (!joining.compareAndSet(false, true)) return
        discovery.stopAdvertising()
        session.join(LanGame("direct", gameKey, host, SocketAddress(host, port), 0))
    }

    override fun close() {
        discovery.close()
        session.close()
        keepRadioAwake(false)
    }
}
