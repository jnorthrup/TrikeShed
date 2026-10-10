package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform endianness contract — runs on all KMP targets.
 * Each `actual` implementation probes endianness at runtime:
 *   - JVM:  java.nio.ByteOrder.nativeOrder()
 *   - JS:   TypedArray byte-level probe
 *   - WASM: JS host TypedArray probe (WASM linear memory is LE by spec)
 *   - POSIX: kotlinx.cinterop IntVar/ByteVar reinterpret probe
 */
class PlatformEndiannessTest {

    @Test
    fun `native byte order is BIG or LITTLE`() {
        val order = ByteOrder.nativeOrder()
        assertTrue(
            order == ByteOrder.BIG_ENDIAN || order == ByteOrder.LITTLE_ENDIAN,
            "nativeOrder must be BIG_ENDIAN or LITTLE_ENDIAN, got $order"
        )
    }

    @Test
    fun `isLittleEndian matches native byte order`() {
        assertEquals(
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN,
            PlatformCodec.isLittleEndian
        )
    }

    @Test
    fun `assumed codec is network endian on every host`() {
        val codec = PlatformCodec.currentPlatformCodec
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), codec.writeInt(0x01020304))
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            codec.writeLong(0x0102030405060708L)
        )
        for (value in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x01020304)) {
            assertEquals(value, codec.readInt(codec.writeInt(value)))
        }
        for (value in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0102030405060708L)) {
            assertEquals(value, codec.readLong(codec.writeLong(value)))
        }
    }

    @Test
    fun `little endian is selected and never assumed`() {
        val codec = PlatformCodec.codec(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), codec.writeInt(0x01020304))
        assertEquals(0x0102030405060708L, codec.readLong(byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)))
        assertTrue(PlatformCodec.codec(ByteOrder.BIG_ENDIAN) === PlatformCodec.currentPlatformCodec)
    }

    @Test
    fun `little-endian long access is LSB first at an unaligned index`() {
        // 0x84 and 0xF8 set the sign bit of each 32-bit half.
        val bytes = byteArrayOf(0x11, 0x01, 0x02, 0x03, 0x84.toByte(), 0x05, 0x06, 0x07, 0xF8.toByte(), 0x22)
        val value = 0xF807060584030201uL.toLong()
        assertEquals(value, bytes.littleEndianGetLongAt(1))
        assertEquals(value, bytes.littleEndianGetLongAtCommonImpl(1))
        val stored = byteArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0x22)
        stored.littleEndianSetLongAt(1, value)
        assertContentEquals(bytes, stored)
        val storedCommon = byteArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0x22)
        storedCommon.littleEndianSetLongAtCommonImpl(1, value)
        assertContentEquals(bytes, storedCommon)
    }
}
