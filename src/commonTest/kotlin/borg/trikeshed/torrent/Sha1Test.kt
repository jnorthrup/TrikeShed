package borg.trikeshed.torrent

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha1Test {
    
    private fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    @Test
    fun testEmptyString() {
        val hash = Sha1.digest("".encodeToByteArray()).toHex()
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hash)
    }
    
    @Test
    fun testAbc() {
        val hash = Sha1.digest("abc".encodeToByteArray()).toHex()
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hash)
    }
    
    @Test
    fun testOneMillionA() {
        val bytes = ByteArray(1_000_000) { 'a'.code.toByte() }
        val hash = Sha1.digest(bytes).toHex()
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", hash)
    }
}
