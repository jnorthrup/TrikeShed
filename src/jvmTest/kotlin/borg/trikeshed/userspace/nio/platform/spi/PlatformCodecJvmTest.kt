package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformCodecJvmTest {
    @Test
    fun `native byte order matches jvm`() {
        val jvmLittleEndian = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN
        val expectedOrder = if (jvmLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        assertEquals(jvmLittleEndian, PlatformCodec.isLittleEndian)
        assertEquals(!jvmLittleEndian, PlatformCodec.isNetworkEndian)
        assertEquals(expectedOrder.toString(), ByteOrder.nativeOrder().toString())
    }

    @Test
    fun `network endian codec is canonical big endian`() {
        val wireInt = PlatformCodec.networkEndianCodec.writeInt(0x01020304)
        val wireLong = PlatformCodec.networkEndianCodec.writeLong(0x0102030405060708L)

        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), wireInt)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), wireLong)
        assertEquals(0x01020304, PlatformCodec.networkEndianCodec.readInt(wireInt))
        assertEquals(0x0102030405060708L, PlatformCodec.networkEndianCodec.readLong(wireLong))
    }

    @Test
    fun `platform codec and network codec diverge on little endian jvm hosts`() {
        if (PlatformCodec.isLittleEndian) {
            assertFalse(PlatformCodec.isNetworkEndian)
            assertContentEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), PlatformCodec.currentPlatformCodec.writeInt(0x01020304))
            assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), PlatformCodec.networkEndianCodec.writeInt(0x01020304))
        } else {
            assertTrue(PlatformCodec.isNetworkEndian)
            assertContentEquals(PlatformCodec.currentPlatformCodec.writeInt(0x01020304), PlatformCodec.networkEndianCodec.writeInt(0x01020304))
        }
    }
}
