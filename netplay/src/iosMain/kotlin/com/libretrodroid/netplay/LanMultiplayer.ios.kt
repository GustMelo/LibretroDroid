package com.libretrodroid.netplay

import platform.UIKit.UIDevice

fun iosLanMultiplayer(
    emulator: NetplayEmulator,
    gameKey: String,
    maxPlayers: Int,
    onStatus: (NetplayStatus) -> Unit,
): LanMultiplayer = LanMultiplayer(
    emulator = emulator,
    gameKey = gameKey,
    maxPlayers = maxPlayers,
    deviceName = UIDevice.currentDevice.name,
    discovery = BonjourDiscovery(),
    onStatus = onStatus,
)

fun iosLinkLobby(linkKey: String, onPaired: (LinkPeer) -> Unit): LinkLobby =
    LinkLobby(linkKey, UIDevice.currentDevice.name, BonjourDiscovery(), onPaired)
