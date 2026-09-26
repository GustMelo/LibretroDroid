package com.libretrodroid.netplay

import kotlin.concurrent.thread

internal actual fun startThread(name: String, realtime: Boolean, block: () -> Unit) {
    thread(name = name, isDaemon = true, priority = if (realtime) Thread.MAX_PRIORITY else Thread.NORM_PRIORITY) { block() }
}

internal actual fun sleepMillis(millis: Long) = Thread.sleep(millis)

internal actual fun nanoTime(): Long = System.nanoTime()
