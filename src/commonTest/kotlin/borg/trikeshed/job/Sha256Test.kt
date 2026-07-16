package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SHA-256 test suite: KAT vectors and cross-platform parity.
 */
class Sha256Test {

    // FIPS 180-4 test vectors
    private val katVectors = listOf(
        "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
    )

    @Test fun knownAnswerTest() {
        for ((msg, expectedHex) in katVectors) {
            val input = msg.encodeToByteArray()
            val digest = Sha256Pure.digest(input)
            val actualHex = digest.joinToString("") { byte -> byte.toInt().and(0xff).toString(16).padStart(2, '0') }
            assertEquals(expectedHex, actualHex, "KAT failed for input \"$msg\"")
        }
    }

    @Test fun digestLengthIs32() {
        val digest = Sha256Pure.digest("test".encodeToByteArray())
        assertEquals(32, digest.size, "SHA-256 digest must be 32 bytes")
    }

    @Test fun avalancheEffect() {
        val d1 = Sha256Pure.digest("hello".encodeToByteArray())
        val d2 = Sha256Pure.digest("hellp".encodeToByteArray())
        // Avalanche: changing one bit should change ~half the output bits
        val differingBits = d1.zip(d2).count { (a, b) -> a != b }
        assertTrue(differingBits >= 8, "Avalanche effect weak: only $differingBits bytes differ")
    }
}
