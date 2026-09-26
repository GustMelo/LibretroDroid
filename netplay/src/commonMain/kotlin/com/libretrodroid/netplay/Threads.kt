package com.libretrodroid.netplay

internal expect fun startThread(name: String, realtime: Boolean = false, block: () -> Unit)

internal expect fun sleepMillis(millis: Long)

internal expect fun nanoTime(): Long
