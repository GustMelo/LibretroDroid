package com.libretrodroid.netplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

fun LanMultiplayer(
    context: Context,
    emulator: NetplayEmulator,
    gameKey: String,
    maxPlayers: Int,
    onStatus: (NetplayStatus) -> Unit,
): LanMultiplayer {
    val app = context.applicationContext
    val wifiLock = app.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LibretroDroid:netplay")
        .apply { setReferenceCounted(false) }
    return LanMultiplayer(
        emulator = emulator,
        gameKey = gameKey,
        maxPlayers = maxPlayers,
        deviceName = deviceName(app),
        discovery = NsdDiscovery(app),
        keepRadioAwake = { awake -> if (awake) wifiLock.acquire() else wifiLock.release() },
        debugDelay = debugDelay(),
        onStatus = onStatus,
    )
}

private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}"

private fun debugDelay(): Int? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.libretrodroid.delay") as String
}.getOrNull()?.toIntOrNull()
