package com.libretrodroid.netplay

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Closeable
import kotlin.concurrent.Volatile
import kotlin.random.Random

class NetplaySession(
    private val emulator: NetplayEmulator,
    private val gameKey: String,
    private val deviceName: String,
    private val maxPlayers: Int = NetplayProtocol.MAX_PLAYERS,

    private val debugDelay: Int? = null,
    private val onStatus: (NetplayStatus) -> Unit,
) : NetplayListener, Closeable {

    private enum class Role { NONE, HOST, CLIENT }

    private class Peer(
        val connection: TcpConnection,
        val name: String,
        @Volatile var datagramAddress: SocketAddress,
        @Volatile var rttMillis: Double,
        @Volatile var jitterMillis: Double = 0.0,

        val recompiler: Boolean = false,
        val linkSave: ByteString = ByteString.EMPTY,
        @Volatile var port: Int = 0,
        @Volatile var lastHeardNanos: Long = nanoTime(),
    ) {
        val sink: BufferedSink get() = connection.sink
        val sinkLock = SynchronizedObject()
        val quality = NetworkQuality()
        val playback = PlaybackHealth()
        @Volatile var timingSequence = 0
    }

    private val udp = UdpSocket.bind(UDP_PORT)

    @Volatile private var closed = false
    @Volatile private var role = Role.NONE
    @Volatile private var myPort = 0
    @Volatile private var history: InputHistory? = null

    @Volatile private var session = 0

    private val historyLock = SynchronizedObject()
    private val inputBuffer = ByteArray(8 + NetplayProtocol.RESEND_WINDOW * 2)

    private val roster = SynchronizedObject()
    @Volatile private var lastInputSentAt = 0L
    @Volatile private var hostPeer: Peer? = null
    @Volatile private var server: TcpServer? = null

    private val clients = atomic(emptyList<Peer>())

    private val pongs = Channel<Pair<SocketAddress, Double>>(32)

    private val learned = LockedMap<Int, CompletableDeferred<SocketAddress>>()
    private val nonce = atomic(0)
    private val pendingPings = LockedMap<Int, Pair<SocketAddress, Long>>()
    private val probeLock = SynchronizedObject()
    private val delayGovernor = DelayGovernor()
    private val playback = PlaybackHealth()
    private var delayFloor = 0
    private val observedTiming = HashMap<Peer, Int>()
    @Volatile private var probing: SocketAddress? = null
    private val localHashes = LockedMap<Int, Long>()
    private val remoteHashes = LockedMap<Long, Long>()
    @Volatile private var lastResyncAt = 0L
    @Volatile private var resyncPending = false
    @Volatile private var lastStatus: NetplayStatus = NetplayStatus.Solo
    @Volatile private var punching = false

    val isAlone: Boolean get() = role == Role.NONE

    init {
        startThread("netplay-udp", realtime = true) { receiveLoop() }
        startThread("netplay-ping") { pingLoop() }
        startThread("netplay-resend") { resendLoop() }
    }

    fun isOwnServer(game: LanGame): Boolean {
        val port = server?.localPort ?: return false
        return game.address.port == port && isLocalAddress(game.address.host)
    }

    fun host(): Int {
        val socket = TcpServer.bind(TCP_PORT)
        server = socket
        startThread("netplay-accept") {
            while (!closed) {
                val client = socket.accept() ?: break
                startThread("netplay-client") { serveClient(client) }
            }
        }
        return socket.localPort
    }

    private fun serveClient(connection: TcpConnection) {
        var peer: Peer? = null
        try {
            val hello = ControlMessage.read(connection.source) as? ControlMessage.Hello ?: return
            val rejection = when {
                hello.version != NetplayProtocol.VERSION -> RejectReason.APP_VERSION
                hello.gameKey != gameKey -> RejectReason.GAME
                clients.value.size + 1 >= maxPlayers -> RejectReason.FULL
                else -> null
            }
            if (rejection != null) {
                connection.sink.write(ControlMessage.encode(ControlMessage.Reject(rejection.wire))).flush()
                return
            }
            val token = Random.nextInt()
            val arrival = CompletableDeferred<SocketAddress>().also { learned[token] = it }
            connection.sink.write(ControlMessage.encode(ControlMessage.Welcome(clients.value.size + 1, udp.localPort, deviceName, token))).flush()

            val datagramAddress = runBlocking { withTimeoutOrNull(3_000) { arrival.await() } }
                ?: SocketAddress(connection.remote.host, hello.datagramPort)
            learned.remove(token)

            val joinedRtt = synchronized(probeLock) { measureRtt(datagramAddress) }
            val joined = Peer(connection, hello.name, datagramAddress, joinedRtt.rtt, joinedRtt.jitter, hello.recompiler, hello.linkSave)
            peer = joined
            synchronized(roster) {
                if (clients.value.size + 1 >= maxPlayers) {
                    connection.sink.write(ControlMessage.encode(ControlMessage.Reject(RejectReason.FULL.wire))).flush()
                    return
                }
                clients.value = clients.value + joined
                restartLockstep()
            }
            while (!closed) {
                when (ControlMessage.read(connection.source)) {
                    ControlMessage.Bye -> break
                    ControlMessage.RestartRequest -> {
                        netplayLog("restart requested by ${joined.name}")
                        restartFromHost(emulator::reset)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            netplayLog("client left: ${e.message}")
        } finally {
            runCatching { connection.close() }
            val left = peer
            if (left != null && !closed) synchronized(roster) {
                clients.value = clients.value - left
                restartLockstep()
            }
        }
    }

    private fun restartLockstep(delayOverride: Int? = null) {
        val peers = clients.value
        if (peers.isEmpty()) {
            role = Role.NONE
            history = null
            emulator.stop()
            if (linkLayout.isNotEmpty()) {
                // The others kept their saves on their own devices: only this player's console stays.
                emulator.linkConsoles(1, emptyMap(), rebuild = false)
                linkLayout = emptyList()
            }
            emulator.setRecompiler(emulator.canRecompile)
            publish(NetplayStatus.Solo)
            return
        }
        peers.forEachIndexed { index, peer -> peer.port = index + 1 }
        val players = peers.size + 1
        val delay = debugDelay ?: delayOverride ?: maxOf(delayFloor, peers.maxOf { inputDelayFor(it.rttMillis, emulator.rollback, emulator.minInputDelay, jitterMillis = it.jitterMillis) })
        localHashes.clear()
        remoteHashes.clear()
        myPort = 0

        emulator.stop()

        val recompiler = emulator.canRecompile && peers.all { it.recompiler }
        emulator.setRecompiler(recompiler)
        session = (session + 1) and 0xFF
        synchronized(historyLock) { history = InputHistory(0, session) }
        role = Role.HOST
        val linked = emulator.linkMaxPlayers > 1
        if (linked) prepareLinkedConsoles(peers)
        val start = emulator.startAsHost(players, delay)
        if (linked) emulator.setLinkLocal(0)
        val state = start.state.toByteString()
        // Linked consoles carry every save in the state; a shared save would replace the joiner's own.
        val saveRam = if (linked) ByteString.EMPTY else start.saveRam?.toByteString() ?: ByteString.EMPTY
        peers.forEach { peer ->
            runCatching {
                synchronized(peer.sinkLock) {
                    peer.sink.write(ControlMessage.encode(ControlMessage.Start(peer.port, players, delay, state, session, recompiler, saveRam))).flush()
                }
            }
        }
        lastResyncAt = nanoTime()
        resyncPending = false
        publishPlaying(delay)
    }

    /** Players in port order when the consoles were last laid out (index + 1 is the port). */
    private var linkLayout: List<Peer> = emptyList()

    /**
     * One console per player. A newcomer's console boots with the save they sent; when a player left and
     * ports moved, every console but the host's boots again with its player's current save, so nothing saved
     * in the session is lost.
     */
    private fun prepareLinkedConsoles(peers: List<Peer>) {
        val kept = linkLayout
        val moved = kept.withIndex().any { (index, peer) -> peers.getOrNull(index) !== peer }
        val saves = HashMap<Int, ByteArray>()
        if (moved) {
            val current = kept.withIndex().associate { (index, peer) -> peer to emulator.linkConsoleSave(index + 1) }
            peers.forEachIndexed { index, peer -> saves[index + 1] = current[peer] ?: peer.linkSave.toByteArray() }
        } else {
            peers.forEachIndexed { index, peer -> if (index >= kept.size) saves[index + 1] = peer.linkSave.toByteArray() }
        }
        emulator.linkConsoles(peers.size + 1, saves, rebuild = moved)
        linkLayout = peers
    }

    fun restartFromHost(prepare: () -> Unit) = startThread("netplay-restart") {
        synchronized(roster) {
            if (role == Role.HOST) {
                emulator.stop()
                prepare()
                restartLockstep()
            } else {
                prepare()
            }
        }
    }

    fun restart() {
        val host = hostPeer
        if (role != Role.CLIENT || host == null) return restartFromHost(emulator::reset)
        startThread("netplay-restart-request") {
            runCatching { synchronized(host.sinkLock) { host.sink.write(ControlMessage.encode(ControlMessage.RestartRequest)).flush() } }
                .onFailure { netplayLog("restart request failed: ${it.message}") }
        }
    }

    fun join(game: LanGame) = startThread("netplay-join") {
        try {
            val connection = TcpConnection.connect(game.address, timeoutMillis = 3_000)
            val linkSave = if (emulator.linkMaxPlayers > 1) emulator.linkLocalSave()?.toByteString() else null
            connection.sink.write(ControlMessage.encode(ControlMessage.Hello(
                NetplayProtocol.VERSION, gameKey, deviceName, udp.localPort, emulator.canRecompile, linkSave ?: ByteString.EMPTY,
            ))).flush()

            when (val reply = ControlMessage.read(connection.source)) {
                is ControlMessage.Reject -> {
                    connection.close()
                    publish(NetplayStatus.Ended(NetplayEnd.Rejected(RejectReason.fromWire(reply.reason), reply.reason)))
                    return@startThread
                }
                is ControlMessage.Welcome -> {

                    val hostAddress = SocketAddress(connection.remote.host, reply.datagramPort)
                    hostPeer = Peer(connection, reply.hostName, hostAddress, rttMillis = 0.0)
                    punch(hostAddress, reply.token)
                }
                else -> error("unexpected reply: $reply")
            }
            role = Role.CLIENT
            while (!closed) {
                when (val message = ControlMessage.read(connection.source)) {
                    is ControlMessage.Start -> {
                        punching = false
                        myPort = message.port

                        emulator.stop()
                        emulator.setRecompiler(message.recompiler)
                        session = message.session
                        synchronized(historyLock) { history = InputHistory(message.port, message.session) }
                        val saveRam = message.saveRam.takeIf { it.size > 0 }?.toByteArray()
                        if (!emulator.startAsClient(message.port, message.players, message.inputDelay, message.state.toByteArray(), saveRam)) {
                            error("incompatible host state")
                        }
                        if (emulator.linkMaxPlayers > 1) emulator.setLinkLocal(message.port)
                        publishPlaying(message.inputDelay, players = message.players)
                    }
                    ControlMessage.Bye -> break
                    else -> Unit
                }
            }
            publish(NetplayStatus.Ended(NetplayEnd.HostEnded))
        } catch (e: Exception) {
            if (!closed) publish(NetplayStatus.Ended(NetplayEnd.ConnectionLost(e.message)))
        } finally {
            role = Role.NONE
            history = null
            hostPeer?.let { runCatching { it.connection.close() } }
            hostPeer = null
            emulator.stop()
            if (emulator.linkMaxPlayers > 1) emulator.linkKeepLocal()
            emulator.setRecompiler(emulator.canRecompile)
        }
    }

    private fun punch(host: SocketAddress, token: Int) {
        punching = true
        startThread("netplay-punch") {
            val hello = Datagram.encode(Datagram.Hello(token))
            val deadline = nanoTime() + 5_000_000_000L
            while (punching && !closed && nanoTime() < deadline) {
                send(hello, hello.size, host)
                sleepMillis(50)
            }
        }
    }

    override fun onLocalInput(frame: Int, buttons: Int) {
        synchronized(historyLock) {
            val length = history?.recordEncoded(frame, buttons, inputBuffer) ?: return
            sendInput(inputBuffer, length)
        }
    }

    private fun sendInput(bytes: ByteArray, length: Int) {
        lastInputSentAt = nanoTime()
        when (role) {
            Role.HOST -> clients.value.forEach { send(bytes, length, it.datagramAddress) }
            Role.CLIENT -> hostPeer?.let { send(bytes, length, it.datagramAddress) }
            Role.NONE -> Unit
        }
    }

    private fun resendLoop() {
        while (!closed) {
            sleepMillis(RESEND_CHECK_MILLIS)
            if (role == Role.NONE || nanoTime() - lastInputSentAt < RESEND_AFTER_NANOS) continue
            synchronized(historyLock) {
                val length = history?.resendEncoded(inputBuffer) ?: 0
                if (length > 0) sendInput(inputBuffer, length)
            }
        }
    }

    override fun onStateDiagnostic(frame: Int, block: Int, hash: Long) {
        netplayLog("state-block session=$session port=$myPort frame=$frame offset=${block * 65536} hash=${hash.toULong().toString(16)}")
    }

    override fun onPerformance(frame: Int, counters: Long) {
        playback.record(frame, counters, session, nanoTime())
        if (role == Role.CLIENT) hostPeer?.let { host ->
            val bytes = Datagram.encode(Datagram.Performance(myPort, frame, counters, session))
            send(bytes, bytes.size, host.datagramAddress)
        }
    }

    override fun onStateHash(frame: Int, hash: Long) {
        when (role) {
            Role.HOST -> {
                localHashes[frame] = hash
                clients.value.forEach { peer -> remoteHashes.remove(key(peer.port, frame))?.let { compare(frame, hash, it) } }
                localHashes.removeKeysIf { it < frame - HASH_MEMORY }

                remoteHashes.removeKeysIf { (it and 0xFFFFFFFFL) < frame - HASH_MEMORY }
            }
            Role.CLIENT -> hostPeer?.let { host ->
                val bytes = Datagram.encode(Datagram.StateHash(myPort, frame, hash, session))
                send(bytes, bytes.size, host.datagramAddress)
            }
            Role.NONE -> Unit
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(1_500)
        while (!closed) {
            val packet = try {
                udp.receive(buffer) ?: return
            } catch (e: Exception) {
                if (closed) return else continue
            }
            val datagram = Datagram.decode(buffer, packet.length) ?: continue
            val from = packet.from
            peerAt(from)?.lastHeardNanos = nanoTime()
            when (datagram) {
                is Datagram.Input -> {
                    val sender = peerAt(from) ?: continue
                    if (role == Role.NONE || datagram.port == myPort || datagram.session != session) continue
                    if (role == Role.HOST && sender.port != datagram.port) continue
                    sender.quality.input(datagram.lastFrame, datagram.session, datagram.port)
                    datagram.buttons.forEachIndexed { i, buttons ->
                        emulator.pushInput(datagram.port, datagram.firstFrame + i, buttons)
                    }

                    if (role == Role.HOST) clients.value.forEach {
                        if (it.port != datagram.port) send(buffer, packet.length, it.datagramAddress)
                    }
                }
                is Datagram.StateHash -> if (role == Role.HOST && datagram.session == session && peerAt(from)?.port == datagram.port) {
                    val local = localHashes[datagram.frame]
                    if (local != null) compare(datagram.frame, local, datagram.hash)
                    else remoteHashes[key(datagram.port, datagram.frame)] = datagram.hash
                }
                is Datagram.Performance -> {
                    val peer = peerAt(from) ?: continue
                    if (role != Role.HOST || peer.port != datagram.port || datagram.session != session) continue
                    if (peer.playback.record(datagram.frame, datagram.counters, session, nanoTime())) {
                        val value = datagram.counters
                        netplayLog("peer ${peer.port} stalls=${value and 65535} replayMax=${(value ushr 16) and 65535} rollbacks=${(value ushr 32) and 65535} replayed=${(value ushr 48) and 65535}")
                    }
                }
                is Datagram.Hello -> learned[datagram.token]?.complete(from)
                is Datagram.Ping -> {
                    val pong = Datagram.encode(Datagram.Pong(datagram.nonce, datagram.sentAtNanos))
                    send(pong, pong.size, from)
                }
                is Datagram.Pong -> {
                    val pending = pendingPings[datagram.nonce] ?: continue
                    if (pending.first != from || pending.second != datagram.sentAtNanos) continue
                    pendingPings.remove(datagram.nonce)
                    val rtt = (nanoTime() - pending.second) / 1_000_000.0
                    if (probing == from) pongs.trySend(from to rtt)
                    peerAt(from)?.let { peer ->
                        val quality = peer.quality.rtt(rtt)
                        peer.rttMillis = quality.p90
                        peer.jitterMillis = quality.jitter
                        peer.timingSequence++
                    }
                }
            }
        }
    }

    private fun peerAt(address: SocketAddress): Peer? =
        clients.value.firstOrNull { it.datagramAddress == address } ?: hostPeer?.takeIf { it.datagramAddress == address }

    private fun send(bytes: ByteArray, length: Int, address: SocketAddress) {
        runCatching { udp.send(bytes, length, address) }
    }

    private data class RttMeasurement(val rtt: Double, val jitter: Double)

    private fun measureRtt(address: SocketAddress): RttMeasurement {
        probing = address
        while (pongs.tryReceive().isSuccess) { }
        repeat(12) {
            sendPing(address)
            sleepMillis(15)
        }
        val samples = runBlocking {
            buildList {
                withTimeoutOrNull(500) {
                    while (size < 12) {
                        val (from, rtt) = pongs.receive()
                        if (from == address) add(rtt)
                    }
                }
            }
        }.sorted()
        probing = null
        if (samples.isEmpty()) return RttMeasurement(FALLBACK_RTT_MS, 0.0)
        val p90 = samples[kotlin.math.ceil(samples.size * 0.9).toInt() - 1]
        val median = samples[samples.size / 2]
        val jitter = samples.map { kotlin.math.abs(it - median) }.sorted()[samples.size / 2]
        return RttMeasurement(p90, jitter)
    }

    private fun sendPing(address: SocketAddress) {
        val now = nanoTime()
        pendingPings.removeValuesIf { now - it.second > 5_000_000_000L }
        val id = nonce.incrementAndGet()
        pendingPings[id] = address to now
        val ping = Datagram.encode(Datagram.Ping(id, now))
        send(ping, ping.size, address)
    }

    private fun pingLoop() {
        while (!closed) {
            sleepMillis(2_000)
            val targets = if (role == Role.HOST) clients.value.map { it.datagramAddress } else listOfNotNull(hostPeer?.datagramAddress)
            targets.forEach { address ->
                sendPing(address)
            }

            val now = nanoTime()
            (clients.value + listOfNotNull(hostPeer)).forEach { peer ->
                netplayLog("peer ${peer.port} rttP90=${peer.rttMillis.toInt()}ms jitter=${peer.jitterMillis.toInt()}ms ${peer.quality.inputsSummary()}")
                if (now - peer.lastHeardNanos > SILENCE_LIMIT_NANOS) runCatching { peer.connection.close() }
            }
            if (!closed && role == Role.HOST && resyncPending && now - lastResyncAt > RESYNC_INTERVAL_NANOS) synchronized(roster) {
                resyncPending = false
                netplayLog("resynchronizing peers from host state")
                restartLockstep()
            }
            if (!closed && role == Role.HOST && debugDelay == null) synchronized(roster) {
                val playing = lastStatus as? NetplayStatus.Playing
                val peers = clients.value
                observedTiming.keys.retainAll(peers.toSet())
                if (playing != null && peers.isNotEmpty() && peers.all { it.timingSequence > (observedTiming[it] ?: 0) }) {
                    peers.forEach { observedTiming[it] = it.timingSequence }
                    val networkDelay = peers.maxOf {
                        inputDelayFor(it.rttMillis, emulator.rollback, emulator.minInputDelay, jitterMillis = it.jitterMillis)
                    }
                    val struggling = playback.degraded(now) || peers.any { it.playback.degraded(now) }
                    val limit = if (emulator.rollback) 4 else NetplayProtocol.MAX_INPUT_DELAY
                    val recommended = maxOf(networkDelay, if (struggling) minOf(playing.inputDelay + 1, limit) else 0)
                    delayGovernor.observe(playing.inputDelay, recommended, now)?.let { next ->
                        delayFloor = next
                        netplayLog("sustained network/playback pressure: synchronizing input delay ${playing.inputDelay} -> $next")
                        restartLockstep(next)
                    }
                }
            }
            (lastStatus as? NetplayStatus.Playing)?.let { publishPlaying(it.inputDelay, it.players) }
        }
    }

    private fun compare(frame: Int, local: Long, remote: Long) {
        if (local == remote) return
        netplayLog("desync at frame $frame")
        resyncPending = true
    }

    private fun key(port: Int, frame: Int): Long = (port.toLong() shl 32) or (frame.toLong() and 0xFFFFFFFFL)

    private fun publishPlaying(delay: Int, players: Int = clients.value.size + 1) {
        val rtt = if (role == Role.HOST) clients.value.maxOfOrNull { it.rttMillis } else hostPeer?.rttMillis
        publish(NetplayStatus.Playing(role == Role.HOST, myPort, players, delay, rtt))
    }

    private fun publish(status: NetplayStatus) {
        lastStatus = status
        onStatus(status)
    }

    override fun close() {
        if (closed) return
        closed = true
        val bye = ControlMessage.encode(ControlMessage.Bye)
        (clients.value + listOfNotNull(hostPeer)).forEach { peer ->
            runCatching { synchronized(peer.sinkLock) { peer.sink.write(bye).flush() } }
            runCatching { peer.connection.close() }
        }
        runCatching { server?.close() }
        runCatching { udp.close() }
        if (role != Role.NONE) emulator.stop()
    }

    private companion object {
        const val TCP_PORT = 53318
        const val UDP_PORT = 53319
        const val HASH_MEMORY = 1_200
        const val FALLBACK_RTT_MS = 20.0
        const val RESEND_CHECK_MILLIS = 16L
        const val RESEND_AFTER_NANOS = 40_000_000L
        const val SILENCE_LIMIT_NANOS = 4_500_000_000L
        const val RESYNC_INTERVAL_NANOS = 30_000_000_000L
    }
}

private class LockedMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) { map[key] }
    operator fun set(key: K, value: V) = synchronized(lock) { map[key] = value }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    fun clear() = synchronized(lock) { map.clear() }
    fun removeValuesIf(predicate: (V) -> Boolean) = synchronized(lock) { map.entries.removeAll { predicate(it.value) } }
    fun removeKeysIf(predicate: (K) -> Boolean) = synchronized(lock) { map.keys.removeAll(predicate) }
}
