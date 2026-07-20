package borg.trikeshed.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Blake3HashTest {

    @Test
    fun testBlake3HashIdentity() {
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(1, 2, 3)
        val bytes3 = byteArrayOf(3, 2, 1)

        val hash1 = Blake3Hash.hash(bytes1)
        val hash2 = Blake3Hash.hash(bytes2)
        val hash3 = Blake3Hash.hash(bytes3)

        assertEquals(hash1, hash2)
        assertNotEquals(hash1, hash3)
        assertEquals(32, hash1.bytes.size)
    }
}
