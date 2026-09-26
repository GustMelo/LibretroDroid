package com.libretrodroid.netplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class NetworkQualityTest {
    @Test
    fun playbackPressureRequiresFreshConsecutiveReports() {
        val health = PlaybackHealth()
        health.record(300, 4L shl 16, 1, 0)
        assertEquals(false, health.degraded(0))
        assertEquals(false, health.record(300, 4L shl 16, 1, 1))
        health.record(600, 4L shl 16, 1, 5_000_000_000)
        assertTrue(health.degraded(5_000_000_000))
        assertEquals(false, health.degraded(18_000_000_000))
        health.record(300, 4L shl 16, 2, 20_000_000_000)
        assertEquals(false, health.degraded(20_000_000_000))
    }

    @Test
    fun percentilesRetainSpikesWithoutUnboundedHistory() {
        val quality = NetworkQuality()
        repeat(27) { quality.rtt(4.0) }
        repeat(3) { quality.rtt(80.0) }
        assertEquals(80.0, quality.rtt(80.0).p90)
        repeat(30) { quality.rtt(4.0) }
        assertEquals(4.0, quality.rtt(4.0).p90)
    }

    @Test
    fun governorRequiresPersistenceAndCooldown() {
        val governor = DelayGovernor()
        assertNull(governor.observe(1, 2, 0))
        assertNull(governor.observe(1, 2, 2_000_000_000))
        assertEquals(2, governor.observe(1, 2, 4_000_000_000))
        repeat(10) { assertNull(governor.observe(2, 3, 6_000_000_000)) }
        assertEquals(3, governor.observe(2, 4, 65_000_000_000))
        assertNull(governor.observe(3, 1, 130_000_000_000))
    }

    @Test
    fun jitterRaisesTheBudget() {
        assertEquals(1, inputDelayFor(8.0, rollback = true, minDelay = 1))
        assertEquals(2, inputDelayFor(8.0, rollback = true, minDelay = 1, jitterMillis = 30.0))
    }

    @Test
    fun reusableEncodingMatchesWireFormatAcrossWrapAndGap() {
        val encoded = InputHistory(3, 240)
        val reference = InputHistory(3, 240)
        val buffer = ByteArray(136)
        (0..100).forEach { frame ->
            val size = encoded.recordEncoded(frame, frame * 129, buffer)
            assertEquals(reference.record(frame, frame * 129), Datagram.decode(buffer, size))
        }
        assertEquals(reference.resend(), Datagram.decode(buffer, encoded.resendEncoded(buffer)))
        assertEquals(reference.record(500, 65535), Datagram.decode(buffer, encoded.recordEncoded(500, 65535, buffer)))
    }

    @Test
    fun fourInputStreamsRecoverWithLossJitterAndReordering() {
        data class Packet(val tick: Int, val bytes: ByteArray)
        for (loss in listOf(0.01, 0.03, 0.05)) {
            val random = Random(4096)
            val pending = mutableListOf<Packet>()
            val expected = Array(4) { IntArray(1800) }
            val recovered = Array(4) { IntArray(1800) { -1 } }
            val histories = Array(4) { InputHistory(it, 42) }
            val buffer = ByteArray(136)
            repeat(1800) { frame ->
                repeat(4) { port ->
                    val buttons = random.nextInt(65536)
                    expected[port][frame] = buttons
                    val size = histories[port].recordEncoded(frame, buttons, buffer)
                    if (random.nextDouble() >= loss) pending += Packet(frame + random.nextInt(5), buffer.copyOf(size))
                }
            }
            repeat(4) { port ->
                val size = histories[port].resendEncoded(buffer)
                pending += Packet(1805, buffer.copyOf(size))
            }
            pending.sortedBy { it.tick }.forEach { packet ->
                val input = Datagram.decode(packet.bytes) as Datagram.Input
                input.buttons.forEachIndexed { index, buttons ->
                    recovered[input.port][input.firstFrame + index] = buttons
                }
            }
            repeat(4) { assertTrue(expected[it].contentEquals(recovered[it]), "loss=$loss port=$it") }
        }
    }
}
