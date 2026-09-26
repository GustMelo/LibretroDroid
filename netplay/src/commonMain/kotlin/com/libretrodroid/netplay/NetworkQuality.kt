package com.libretrodroid.netplay

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.abs
import kotlin.math.ceil

internal class NetworkQuality {
    data class Timing(val p90: Double, val jitter: Double)
    private val lock = SynchronizedObject()
    private val samples = DoubleArray(30)
    private var count = 0
    private var cursor = 0
    private val latest = IntArray(NetplayProtocol.MAX_PLAYERS) { -1 }
    private var generation = -1
    private var packets = 0L
    private var gaps = 0L
    private var repeats = 0L

    fun rtt(millis: Double): Timing = synchronized(lock) {
        require(millis.isFinite() && millis >= 0)
        samples[cursor] = millis
        cursor = (cursor + 1) % samples.size
        count = minOf(count + 1, samples.size)
        val sorted = samples.copyOf(count).sorted()
        val median = sorted[count / 2]
        Timing(sorted[ceil(count * 0.9).toInt() - 1], sorted.map { abs(it - median) }.sorted()[count / 2])
    }

    fun input(frame: Int, session: Int, port: Int) = synchronized(lock) {
        if (generation != session) {
            latest.fill(-1)
            packets = 0
            gaps = 0
            repeats = 0
            generation = session
        }
        val previous = latest[port]
        packets++
        if (previous >= 0) {
            if (frame > previous + 1) gaps += frame.toLong() - previous - 1
            if (frame <= previous) repeats++
        }
        latest[port] = maxOf(previous, frame)
    }

    fun inputsSummary(): String = synchronized(lock) {
        "inputPackets=$packets frameGaps=$gaps repeatedOrReordered=$repeats"
    }
}

internal class DelayGovernor {
    private var badSamples = 0
    private var lastIncrease = Long.MIN_VALUE

    fun observe(current: Int, recommended: Int, nowNanos: Long): Int? {
        if (recommended <= current) {
            badSamples = 0
            return null
        }
        badSamples++
        if (badSamples < 3 || (lastIncrease != Long.MIN_VALUE && nowNanos - lastIncrease < 60_000_000_000L)) return null
        badSamples = 0
        lastIncrease = nowNanos
        return current + 1
    }
}

internal class PlaybackHealth {
    private val lock = SynchronizedObject()
    private var streak = 0
    private var lastFrame = -1
    private var generation = -1
    private var lastReport = 0L

    fun record(frame: Int, counters: Long, session: Int, now: Long): Boolean = synchronized(lock) {
        if (generation != session) {
            generation = session
            lastFrame = -1
            streak = 0
        }
        if (frame <= lastFrame) return false
        lastFrame = frame
        lastReport = now
        val stalls = (counters and 65535).toInt()
        val replayMax = ((counters ushr 16) and 65535).toInt()
        streak = if (stalls >= 3 || replayMax >= 4) streak + 1 else 0
        true
    }

    fun degraded(now: Long): Boolean = synchronized(lock) {
        streak >= 2 && now - lastReport < 12_000_000_000L
    }
}
