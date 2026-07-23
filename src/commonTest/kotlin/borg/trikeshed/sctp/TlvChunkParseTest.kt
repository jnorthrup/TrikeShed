package borg.trikeshed.sctp

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class TlvChunkParseTest {
    @Test
    fun testUnknownSkipBehavior() {
        val parser = TlvChunkParser()

        // Chunk 1: Type 0x00 (DATA), Length 8, data = 0xAA 0xBB 0xCC 0xDD
        val chunk1 = byteArrayOf(0x00, 0x00, 0x00, 0x08, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        // Chunk 2: Unknown type with action 10 (skip) -> type = 0x80 (top two bits '10'). Length 8
        val chunk2 = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x08, 0x11, 0x22, 0x33, 0x44)

        // Chunk 3: Type 0x00 (DATA), Length 8. Should BE PARSED since we skipped chunk 2.
        val chunk3 = byteArrayOf(0x00, 0x00, 0x00, 0x08, 0xEE.toByte(), 0xFF.toByte(), 0x00, 0x11)

        val data = chunk1 + chunk2 + chunk3
        val result = parser.parse(data)

        // It must parse chunk 1 and chunk 3.
        assertEquals(2, result.size)
        assertEquals(0x00, result[0].type)
        assertEquals(0xAA.toByte(), result[0].data[0])
        assertEquals(0x00, result[1].type)
        assertEquals(0xEE.toByte(), result[1].data[0])
    }
}
