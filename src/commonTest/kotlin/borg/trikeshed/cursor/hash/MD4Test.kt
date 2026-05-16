package borg.trikeshed.userspace.nio.spi.hash

import borg.trikeshed.userspace.nio.spi.hash.MD4
import kotlin.test.*

/**
 * Ported from columnar/cursors/hash/MD4Test.kt
 */
class MD4Test {
    @Test
    fun testest() {
        val md4 = "test".md4()
        assertEquals("db346d691d7acc4dc2625db19f9e3f52", md4)
    }

    @Test
    fun test1() {
        val md4 = "1".md4()
        assertEquals("8be1ec697b14ad3a53b371436120641d", md4)
    }

    @Test
    fun testMD4Length() {
        val result = MD4.hash("test".encodeToByteArray())
        assertEquals(16, result.size, "MD4 output must be 16 bytes")
    }

    @Test
    fun testMD4HexConversion() {
        val bytes = byteArrayOf(0xa.toByte(), 0x1.toByte())
        assertEquals("0a01", bytes.hex)
    }
}