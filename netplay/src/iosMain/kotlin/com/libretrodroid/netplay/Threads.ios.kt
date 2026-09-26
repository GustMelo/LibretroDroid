package com.libretrodroid.netplay

import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.NSThread
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import platform.posix.usleep

internal actual fun startThread(name: String, realtime: Boolean, block: () -> Unit) {
    NSThread(block = block).apply {
        setName(name)
        qualityOfService = if (realtime) NSQualityOfServiceUserInteractive else NSQualityOfServiceUserInitiated
        start()
    }
}

internal actual fun sleepMillis(millis: Long) {
    usleep((millis * 1_000).toUInt())
}

internal actual fun nanoTime(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
