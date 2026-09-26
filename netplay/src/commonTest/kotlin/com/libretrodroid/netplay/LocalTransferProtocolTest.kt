package com.libretrodroid.netplay

import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalTransferProtocolTest {
    @Test fun roundTripManifest() {
        val manifest = TransferManifest("snes/smw", listOf(
            TransferFile("roms/smw.sfc", 1234, "00".repeat(32).decodeHex(), true),
            TransferFile("saves/smw.srm", 64, "11".repeat(32).decodeHex(), false),
        ))
        assertEquals(manifest, LocalTransferProtocol.decode(LocalTransferProtocol.encode(manifest)))
    }

    @Test fun rejectsUnknownVersion() {
        val bytes = LocalTransferProtocol.encode(TransferManifest("game", emptyList())).copyOf()
        bytes[4] = 2
        assertFailsWith<IllegalArgumentException> { LocalTransferProtocol.decode(bytes) }
    }
}
