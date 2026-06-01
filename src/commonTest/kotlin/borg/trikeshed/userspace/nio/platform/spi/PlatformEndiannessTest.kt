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
    fun `network endian codec always writes big endian`() {
        val wireInt = PlatformCodec.networkEndianCodec.writeInt(0x01020304)
        val wireLong = PlatformCodec.networkEndianCodec.writeLong(0x0102030405060708L)

        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), wireInt)
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            wireLong
        )
    }

    @Test
    fun `native codec int roundtrips`() {
        val value = 0x01020304
        val bytes = PlatformCodec.currentPlatformCodec.writeInt(value)
        assertEquals(value, PlatformCodec.currentPlatformCodec.readInt(bytes))
    }

    @Test
    fun `native codec long roundtrips`() {
        val value = 0x0102030405060708L
        val bytes = PlatformCodec.currentPlatformCodec.writeLong(value)
        assertEquals(value, PlatformCodec.currentPlatformCodec.readLong(bytes))
    }

    @Test
    fun `native and network codecs diverge on little endian hosts`() {
        if (PlatformCodec.isLittleEndian) {
            val native = PlatformCodec.currentPlatformCodec.writeInt(0x01020304)
            val network = PlatformCodec.networkEndianCodec.writeInt(0x01020304)

            assertTrue(
                !native.contentEquals(network),
                "On LE host, native and network byte order must differ"
            )
            assertEquals(0x04, native[0].toInt() and 0xFF)
            assertEquals(0x01, network[0].toInt() and 0xFF)
        }
        // On BE host native == network — nothing to assert
    }

    @Test
    fun `network codec read and write are inverse`() {
        val codec = PlatformCodec.networkEndianCodec
        for (value in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x01020304)) {
            assertEquals(value, codec.readInt(codec.writeInt(value)))
        }
        for (value in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0102030405060708L)) {
            assertEquals(value, codec.readLong(codec.writeLong(value)))
        }
    }
}
