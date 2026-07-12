package borg.trikeshed.reactor

import borg.trikeshed.ws.WebSocketFrame
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportFramingIntegrationTest {

    @Test
    fun testWebSocketFramePartialReadRollback() {
        // A simple masked text frame containing "Hello"
        val fullFrameBytes = byteArrayOf(
            0x81.toByte(), 0x85.toByte(),
            0x37, 0xfa.toByte(), 0x21, 0x3d,
            0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58
        )

        val bs = ByteSeries(fullFrameBytes.toSeries())

        // Feed it byte by byte to ensure parseFrame returns false and rolls back `pos`
        for (i in 1 until fullFrameBytes.size) {
            bs.limit = i
            bs.pos = 0

            val result = WebSocketFrame.parseFrame(bs, borg.trikeshed.ws.FrameHeader())
            assertFalse(result, "Should return false for incomplete frame of size $i")
            assertEquals(0, bs.pos, "Should rollback pos to 0 for incomplete frame of size $i")
        }

        // Now feed the complete frame
        bs.limit = fullFrameBytes.size
        bs.pos = 0

        val result = WebSocketFrame.parseFrame(bs, borg.trikeshed.ws.FrameHeader())
        assertTrue(result, "Should return true for complete frame")
        assertEquals(6, bs.pos, "Should consume the entire frame header")
    }

    @Test
    fun testWebSocketFrameReactorFanoutSeam() {
        val fullFrameBytes = byteArrayOf(
            0x81.toByte(), 0x85.toByte(),
            0x37, 0xfa.toByte(), 0x21, 0x3d,
            0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58
        )

        val bs = ByteSeries(fullFrameBytes.toSeries())
        bs.limit = fullFrameBytes.size
        bs.pos = 0

        val header = borg.trikeshed.ws.FrameHeader()
        val result = WebSocketFrame.parseFrame(bs, header)

        assertTrue(result, "Should parse complete frame")
        assertEquals(5L, header.payloadLength)

        val payload = WebSocketFrame.readPayload(bs, header)
        assertEquals("Hello", payload?.decodeToString(), "Payload should be Hello")
    }

    @Test
    fun testWebSocketFrameCancellationEmitsOnceAndDrains() {
        val bs = ByteSeries(byteArrayOf(0x88.toByte(), 0x00))
        bs.limit = 2
        bs.pos = 0

        val header = borg.trikeshed.ws.FrameHeader()
        val result = WebSocketFrame.parseFrame(bs, header)

        assertTrue(result, "Should parse complete CLOSE frame")
        assertEquals(WebSocketFrame.OpCode.CLOSE, header.opcode, "Opcode should be CLOSE")
        assertEquals(0, header.payloadLength, "Payload length should be 0")

        var cancelEmitted = 0
        val subscriber: (borg.trikeshed.ws.WebSocketFrame.OpCode) -> Unit = { op ->
            if (op == borg.trikeshed.ws.WebSocketFrame.OpCode.CLOSE) {
                cancelEmitted++
            }
        }

        subscriber(header.opcode)

        assertEquals(1, cancelEmitted, "Cancellation/Close should emit exactly once and drain")
    }
}
