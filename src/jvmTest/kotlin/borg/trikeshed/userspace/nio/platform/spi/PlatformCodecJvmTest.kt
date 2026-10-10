package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.nio.ByteOrder
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** java.nio.ByteBuffer is the oracle: big endian until order() selects another. */
class PlatformCodecJvmTest {
    private val longs = longArrayOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0102030405060708L, 0x7F80FF00AA55C3E1L)
    private val doubles = doubleArrayOf(0.0, -0.0, 1.5, -2.25e300, Double.MIN_VALUE, Double.MAX_VALUE)

    private fun nio(order: java.nio.ByteOrder, size: Int, put: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(order).apply(put).array()

    private fun framesAsNio(codec: PlatformCodec, order: java.nio.ByteOrder) {
        for (v in longs) {
            val l = nio(order, 8) { putLong(v) }
            val i = nio(order, 4) { putInt(v.toInt()) }
            val s = nio(order, 2) { putShort(v.toShort()) }
            assertContentEquals(l, codec.writeLong(v)); assertEquals(v, codec.readLong(l))
            assertContentEquals(i, codec.writeInt(v.toInt())); assertEquals(v.toInt(), codec.readInt(i))
            assertContentEquals(s, codec.writeShort(v.toShort())); assertEquals(v.toShort(), codec.readShort(s))
            assertContentEquals(l, codec.writeULong(v.toULong())); assertEquals(v.toULong(), codec.readULong(l))
            assertContentEquals(i, codec.writeUInt(v.toUInt())); assertEquals(v.toUInt(), codec.readUInt(i))
            assertContentEquals(s, codec.writeUShort(v.toUShort())); assertEquals(v.toUShort(), codec.readUShort(s))
        }
        for (d in doubles) {
            val db = nio(order, 8) { putDouble(d) }
            assertContentEquals(db, codec.writeDouble(d)); assertEquals(d, codec.readDouble(db))
            val f = d.toFloat()
            val fb = nio(order, 4) { putFloat(f) }
            assertContentEquals(fb, codec.writeFloat(f)); assertEquals(f, codec.readFloat(fb))
        }
    }

    @Test
    fun `assumed codec frames as a java nio buffer before any order is selected`() =
        framesAsNio(PlatformCodec.currentPlatformCodec, ByteBuffer.allocate(0).order())

    @Test
    fun `selected little endian codec frames as a java nio buffer ordered little endian`() {
        framesAsNio(PlatformCodec.codec(ByteOrder.LITTLE_ENDIAN), java.nio.ByteOrder.LITTLE_ENDIAN)
        assertSame(PlatformCodec.currentPlatformCodec, PlatformCodec.codec(ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun `native order is reported as java nio reports it`() =
        assertEquals(java.nio.ByteOrder.nativeOrder().toString(), ByteOrder.nativeOrder().toString())
}
