package com.swordfish.libretrodroid

import com.libretrodroid.netplay.NetpacketCore

/** All methods, including stop, must run on the GL/emulation thread. */
class AndroidNetpacketCore : NetpacketCore {
    private var failure: Throwable? = null
    private var started = false

    override fun start(localId: Int, send: (Int, ByteArray, Int, Int) -> Unit, pollReceive: () -> Unit): Boolean {
        check(!started)
        failure = null
        started = LibretroDroid.startNetpacket(localId, object : LibretroDroid.NetpacketCallbacks {
            override fun send(flags: Int, data: ByteArray, target: Int) {
                if (failure != null) return
                try { send(flags, data, data.size, target) } catch (e: Throwable) { failure = e }
            }
            override fun pollReceive() {
                if (failure != null) return
                try { pollReceive.invoke() } catch (e: Throwable) { failure = e }
            }
        })
        checkFailure()
        return started
    }
    override fun connected(peerId: Int): Boolean =
        LibretroDroid.connectNetpacket(peerId).also { checkFailure() }
    override fun receive(data: ByteArray, size: Int, sender: Int) {
        require(size in 1..65536 && size <= data.size && sender in 0..3)
        LibretroDroid.receiveNetpacket(data, size, sender)
        checkFailure()
    }
    override fun poll() {
        LibretroDroid.pollNetpacket()
        checkFailure()
    }
    override fun stop() {
        if (!started) return
        LibretroDroid.stopNetpacket()
        started = false
    }
    private fun checkFailure() {
        failure?.let { throw IllegalStateException("Core transport callback failed", it) }
    }
}
