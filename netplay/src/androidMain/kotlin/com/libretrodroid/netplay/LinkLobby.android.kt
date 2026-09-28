package com.libretrodroid.netplay

import android.content.Context

fun LinkLobby(context: Context, linkKey: String, onPaired: (LinkPeer) -> Unit): LinkLobby {
    val app = context.applicationContext
    return LinkLobby(linkKey, deviceName(app), NsdDiscovery(app), onPaired)
}
