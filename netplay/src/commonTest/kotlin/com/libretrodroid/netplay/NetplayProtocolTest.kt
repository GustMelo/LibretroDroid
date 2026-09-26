package com.libretrodroid.netplay

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetplayProtocolTest {

    @Test
    fun datagramsRoundTrip() {
        listOf(
            Datagram.Input(port = 1, lastFrame = 1_000, buttons = listOf(0, 0xFFFF, 0x0101), session = 200),
            Datagram.StateHash(port = 0, frame = 120, hash = -1234567890123L, session = 3),
            Datagram.Ping(nonce = 7, sentAtNanos = 123_456_789_000L),
            Datagram.Pong(nonce = 7, sentAtNanos = 123_456_789_000L),
            Datagram.Performance(3, 600, -1L, 240),
            Datagram.Hello(token = 0x7FFF_1234),
        ).forEach { assertEquals(it, Datagram.decode(Datagram.encode(it))) }
    }

    @Test
    fun inputPacketIsTiny() {

        val packet = Datagram.encode(Datagram.Input(0, 99, List(8) { it }))
        assertEquals(1 + 1 + 1 + 4 + 1 + 8 * 2, packet.size)
    }

    @Test
    fun garbageIsDropped() {
        assertNull(Datagram.decode(byteArrayOf()))
        assertNull(Datagram.decode(byteArrayOf(99, 1, 2)))
        assertNull(Datagram.decode(byteArrayOf(1, 9, 0, 0, 0, 0, 1, 0, 0)))
    }

    @Test
    fun controlMessagesRoundTripThroughAStream() {
        val messages = listOf(
            ControlMessage.Hello(NetplayProtocol.VERSION, "abc:snes9x:1", "Galaxy S22", 40_000, recompiler = true),
            ControlMessage.Welcome(port = 1, datagramPort = 41_000, hostName = "iPhone", token = 99),
            ControlMessage.Reject(RejectReason.GAME.wire),
            ControlMessage.Start(port = 1, players = 2, inputDelay = 2, state = "state".encodeUtf8(), session = 7, recompiler = true, saveRam = "card".encodeUtf8()),
            ControlMessage.Roster(listOf("A", "B")),
            ControlMessage.Bye,
            ControlMessage.RestartRequest,
        )
        val stream = Buffer()
        messages.forEach { stream.write(ControlMessage.encode(it)) }
        assertEquals(messages, messages.map { ControlMessage.read(stream) })
    }

    @Test
    fun historyRepeatsRecentFramesAndRestartsAfterGap() {
        val history = InputHistory(port = 0, redundancy = 3)
        assertEquals(listOf(10), history.record(0, 10).buttons)
        history.record(1, 11)
        history.record(2, 12)
        val packet = history.record(3, 13)
        assertEquals(1, packet.firstFrame)
        assertEquals(listOf(11, 12, 13), packet.buttons)
        assertEquals(listOf(50), history.record(100, 50).buttons)
    }

    @Test
    fun resendCoversTheWholeWindow() {
        val history = InputHistory(port = 1, redundancy = 2, window = 4)
        assertEquals(null, history.resend())
        (0..5).forEach { history.record(it, 100 + it) }
        val packet = history.resend()!!
        assertEquals(2, packet.firstFrame)
        assertEquals(listOf(102, 103, 104, 105), packet.buttons)
        assertEquals(listOf(105, 106), history.record(6, 106).buttons)
    }

    @Test
    fun delayFollowsRoundTrip() {
        assertEquals(2, inputDelayFor(roundTripMillis = 5.0))
        assertEquals(3, inputDelayFor(roundTripMillis = 50.0))
        assertEquals(8, inputDelayFor(roundTripMillis = 900.0))
    }

    @Test
    fun rollbackDelayOnlyCoversTheTrip() {
        assertEquals(0, inputDelayFor(roundTripMillis = 5.0, rollback = true))
        assertEquals(1, inputDelayFor(roundTripMillis = 50.0, rollback = true))
        assertEquals(4, inputDelayFor(roundTripMillis = 900.0, rollback = true))

        assertEquals(1, inputDelayFor(roundTripMillis = 5.0, rollback = true, minDelay = 1))
        assertEquals(4, inputDelayFor(roundTripMillis = 900.0, rollback = true, minDelay = 1))
    }
}
