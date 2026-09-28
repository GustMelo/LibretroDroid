package com.libretrodroid.netplay

import okio.ByteString.Companion.encodeUtf8

interface NetplayEmulator {

    val rollback: Boolean

    val minInputDelay: Int get() = 0

    fun startAsHost(players: Int, inputDelay: Int): HostStart

    fun startAsClient(port: Int, players: Int, inputDelay: Int, state: ByteArray, saveRam: ByteArray?): Boolean
    fun stop()
    fun pushInput(port: Int, frame: Int, buttons: Int)
    fun reset()

    val canRecompile: Boolean get() = false

    fun setRecompiler(enabled: Boolean) = Unit

    /**
     * Consoles the core links inside itself (retrolink), one per player: port p plays console p with its own
     * save and only input crosses the network. 1 means an ordinary game where every port shares one console.
     */
    val linkMaxPlayers: Int get() = 1

    /** Host: one console per player. [saves] boot the consoles they name; [rebuild] boots every console but 0. */
    fun linkConsoles(players: Int, saves: Map<Int, ByteArray>, rebuild: Boolean) = Unit

    /** Host: the save RAM of console [port] as it is now. */
    fun linkConsoleSave(port: Int): ByteArray? = null

    /** This device plays console [port]: its picture, its sound and the save RAM the app keeps. */
    fun setLinkLocal(port: Int) = Unit

    /** The save of the console this device plays, sent to the host when joining. */
    fun linkLocalSave(): ByteArray? = null
}

class HostStart(val state: ByteArray, val saveRam: ByteArray?)

interface NetplayListener {

    fun onLocalInput(frame: Int, buttons: Int)

    fun onStateDiagnostic(frame: Int, block: Int, hash: Long) = Unit

    fun onPerformance(frame: Int, counters: Long) = Unit

    fun onStateHash(frame: Int, hash: Long)
}

sealed interface NetplayStatus {
    data object Solo : NetplayStatus
    data class Playing(
        val isHost: Boolean,

        val port: Int,
        val players: Int,
        val inputDelay: Int,
        val rttMillis: Double?,
    ) : NetplayStatus

    data class Ended(val reason: NetplayEnd) : NetplayStatus
}

sealed interface NetplayEnd {

    data class Rejected(val reason: RejectReason?, val raw: String) : NetplayEnd

    data object HostEnded : NetplayEnd

    data class ConnectionLost(val detail: String?) : NetplayEnd
}

enum class RejectReason(val wire: String) {
    APP_VERSION("app-version"),
    GAME("game"),
    FULL("full"),
    ;

    companion object {
        fun fromWire(wire: String): RejectReason? = entries.firstOrNull { it.wire == wire }
    }
}

fun netplayGameKey(romFingerprint: String, core: String, coreVersion: String): String {
    return "$romFingerprint|$core|$coreVersion|${NetplayProtocol.VERSION}".encodeUtf8().sha1().hex().take(20)
}
