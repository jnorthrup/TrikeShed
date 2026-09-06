package tessera.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VarIntTest {
    @Test fun roundTripsAtEveryWidthBoundary() {
        for (v in listOf(0L, 1L, 0x3FL, 0x40L, 0x3FFFL, 0x4000L, 0x3FFF_FFFFL, 0x4000_0000L, 0x3FFF_FFFF_FFFF_FFFFL)) {
            val buf = ByteCursor(ByteArray(8))
            VarInt.write(buf, v)
            assertEquals(VarInt.size(v), buf.position, "size of $v")
            assertEquals(v, VarInt.read(ByteCursor(buf.array, 0, buf.position)), "value $v")
        }
    }

    @Test fun shortBufferIsSignalledNotIndexed() {
        assertFailsWith<ShortBufferException> { VarInt.read(ByteCursor(ByteArray(0))) }
        assertFailsWith<ShortBufferException> { VarInt.read(ByteCursor(byteArrayOf(0x40.toByte()))) } // says 2 bytes, has 1
    }
}
