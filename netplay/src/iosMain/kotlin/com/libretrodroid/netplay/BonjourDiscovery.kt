package com.libretrodroid.netplay

import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_GAME
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_NAME
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_SESSION
import com.libretrodroid.netplay.LanDiscovery.Companion.KEY_STARTED
import com.libretrodroid.netplay.LanDiscovery.Companion.SERVICE_TYPE
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr

@OptIn(BetaInteropApi::class)
class BonjourDiscovery : LanDiscovery {
    private var published: NSNetService? = null
    private var browser: NSNetServiceBrowser? = null
    private val resolving = mutableListOf<NSNetService>()
    private var onFound: ((LanGame) -> Unit)? = null

    private val delegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
        override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
            resolving += didFindService
            didFindService.delegate = this
            didFindService.resolveWithTimeout(3.0)
        }

        override fun netServiceDidResolveAddress(sender: NSNetService) {
            resolving -= sender
            sender.toLanGame()?.let { game -> onFound?.invoke(game) }
        }

        override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
            resolving -= sender
        }
    }

    override fun advertise(game: LanGame) = onMain {
        published?.stop()
        val service = NSNetService(
            domain = "local.",
            type = "$SERVICE_TYPE.",
            name = "LibretroDroid ${game.hostName}".take(60),
            port = game.address.port,
        )
        service.setTXTRecordData(
            NSNetService.dataFromTXTRecordDictionary(
                mapOf(
                    KEY_SESSION to game.sessionId.toData(),
                    KEY_GAME to game.gameKey.toData(),
                    KEY_NAME to game.hostName.take(40).toData(),
                    KEY_STARTED to game.startedAt.toString().toData(),
                ),
            ),
        )
        service.publish()
        published = service
        netplayLog("announcing ${game.hostName}")
    }

    override fun stopAdvertising() = onMain {
        published?.stop()
        published = null
    }

    override fun discover(onFound: (LanGame) -> Unit) = onMain {
        this.onFound = onFound
        val browser = NSNetServiceBrowser()
        browser.delegate = delegate
        browser.searchForServicesOfType("$SERVICE_TYPE.", inDomain = "local.")
        this.browser = browser
    }

    override fun close() = onMain {
        published?.stop()
        published = null
        browser?.stop()
        browser = null
        resolving.forEach { it.stop() }
        resolving.clear()
        onFound = null
    }

    private fun NSNetService.toLanGame(): LanGame? {
        val txt = TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) } ?: return null
        fun attr(key: String): String? = (txt[key] as? NSData)?.toKString()
        val host = addresses?.mapNotNull { (it as? NSData)?.numericHost() }
            ?.sortedBy { if (it.contains(':')) 1 else 0 }
            ?.firstOrNull() ?: return null
        return LanGame(
            sessionId = attr(KEY_SESSION) ?: return null,
            gameKey = attr(KEY_GAME) ?: return null,
            hostName = attr(KEY_NAME) ?: "?",
            address = SocketAddress(host, port.toInt()),
            startedAt = attr(KEY_STARTED)?.toLongOrNull() ?: return null,
        )
    }
}

private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

@OptIn(BetaInteropApi::class)
private fun String.toData(): NSData = NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)!!

private fun NSData.toKString(): String? = bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt())?.decodeToString()

private fun NSData.numericHost(): String? = memScoped {
    val address: CPointer<sockaddr> = bytes?.reinterpret() ?: return null
    val family = address.pointed.sa_family.toInt()
    if (family != AF_INET && family != AF_INET6) return null
    val host = allocArray<ByteVar>(NI_MAXHOST)
    if (getnameinfo(address, length.convert(), host, NI_MAXHOST.convert(), null, 0u, NI_NUMERICHOST) != 0) return null
    host.toKString().removePrefix("::ffff:")
}
