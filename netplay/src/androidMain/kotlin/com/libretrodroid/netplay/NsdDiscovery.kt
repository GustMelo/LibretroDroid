package com.libretrodroid.netplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_GAME
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_NAME
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_SESSION
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_STARTED
import com.libretrodroid.netplay.LanDiscovery.Companion.SERVICE_TYPE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NsdDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolver = Executors.newSingleThreadExecutor()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    /** Session announced under each service name still on the network: a loss only carries the name. */
    private val sessions = ConcurrentHashMap<String, String>()

    override fun advertise(game: LanGame) {
        val info = NsdServiceInfo().apply {
            serviceName = "LibretroDroid ${game.hostName}".take(60)
            serviceType = SERVICE_TYPE
            port = game.address.port
            setAttribute(KEY_SESSION, game.sessionId)
            setAttribute(KEY_GAME, game.gameKey)
            setAttribute(KEY_NAME, game.hostName.take(40))
            setAttribute(KEY_STARTED, game.startedAt.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "announcing ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "announcement failed: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registration = null; Log.w(TAG, "did not announce: ${it.message}") }
    }

    override fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    override fun discover(onLost: (String) -> Unit, onFound: (LanGame) -> Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                sessions.putIfAbsent(name, UNRESOLVED)
                resolve(info) { game ->
                    // Gone while it was being resolved: reporting it now would leave it listed forever.
                    val previous = sessions.replace(name, game.sessionId) ?: return@resolve
                    if (previous != UNRESOLVED && previous != game.sessionId) onLost(previous)
                    onFound(game)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                sessions.remove(info.serviceName)?.takeIf { it != UNRESOLVED }?.let(onLost)
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(TAG, "discovery failed: $error")
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null; Log.w(TAG, "did not search: ${it.message}") }
    }

    private fun stopDiscovering() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        sessions.clear()
    }

    override fun close() {
        stopAdvertising()
        stopDiscovering()
        resolver.shutdownNow()
    }

    @Suppress("DEPRECATION")
    private fun resolve(found: NsdServiceInfo, onFound: (LanGame) -> Unit) {
        val deliver = { info: NsdServiceInfo -> info.toLanGame()?.let(onFound); Unit }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.registerServiceInfoCallback(found, resolver, object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    deliver(info)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceInfoCallbackRegistrationFailed(error: Int) = Unit
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            })
        } else {
            resolver.execute {
                val done = CountDownLatch(1)
                nsd.resolveService(found, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) { deliver(info); done.countDown() }
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { done.countDown() }
                })
                done.await(3, TimeUnit.SECONDS)
            }
        }
    }

    private fun NsdServiceInfo.toLanGame(): LanGame? {
        val address = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }.firstOrNull() else @Suppress("DEPRECATION") host)
            ?: return null
        fun attr(key: String) = attributes[key]?.decodeToString()
        return LanGame(
            sessionId = attr(KEY_SESSION) ?: return null,
            gameKey = attr(KEY_GAME) ?: return null,
            hostName = attr(KEY_NAME) ?: "?",
            address = SocketAddress(address.hostAddress ?: return null, port),
            startedAt = attr(KEY_STARTED)?.toLongOrNull() ?: return null,
        )
    }

    private companion object {
        const val TAG = "LanDiscovery"
        const val UNRESOLVED = ""
    }
}
