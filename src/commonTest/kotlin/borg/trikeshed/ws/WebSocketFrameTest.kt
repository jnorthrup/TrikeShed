package borg.trikeshed.ws

import borg.trikeshed.lib.ByteSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebSocketFrameTest {

    @Test
    fun testParseFrame_missingData_bufferLessThan2Bytes() {
        // Buffer < 2 bytes
        val buf = ByteSeries(byteArrayOf(0x81.toByte()))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should not have advanced
    }

    @Test
    fun testParseFrame_missingData_extendedLength126() {
        // Buffer has 2 bytes, extended length 126, but missing next 2 bytes
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 126.toByte()))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_extendedLength126_partial() {
        // Buffer has 3 bytes, extended length 126, but missing 1 byte
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 126.toByte(), 0x01.toByte()))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_extendedLength127() {
        // Buffer has 2 bytes, extended length 127, but missing next 8 bytes
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 127.toByte()))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_extendedLength127_partial() {
        // Buffer has 5 bytes, extended length 127, but missing 5 bytes
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 127.toByte(), 0x00, 0x00, 0x00))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_maskKey() {
        // Buffer has enough for header, masking bit set, but missing 4-byte mask key
        // 0x81 = FIN + TEXT, 0x80 = MASK + 0 length
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 0x80.toByte()))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_maskKey_partial() {
        // Buffer has enough for header, masking bit set, but missing 2 bytes of the 4-byte mask key
        // 0x81 = FIN + TEXT, 0x80 = MASK + 0 length
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x01, 0x02))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }

    @Test
    fun testParseFrame_missingData_extendedLength126_and_maskKey_partial() {
        // Extended length 126 (needs 2 more bytes), plus masked (needs 4 more bytes) -> Total 8 bytes
        // Only provide 5 bytes
        val buf = ByteSeries(byteArrayOf(0x81.toByte(), (0x80 or 126).toByte(), 0x00, 0x0A, 0x01))
        val header = FrameHeader()

        assertFalse(WebSocketFrame.parseFrame(buf, header))
        assertEquals(0, buf.pos) // Should be restored to original mark
    }
}
