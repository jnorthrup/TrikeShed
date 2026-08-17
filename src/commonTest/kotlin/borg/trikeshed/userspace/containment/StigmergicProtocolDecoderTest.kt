package borg.trikeshed.userspace.containment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StigmergicProtocolDecoderTest {

    @Test
    fun testNamingPatternDetection() {
        val decoder = StigmergicProtocolDecoder()
        val patch = PatchData("swarm_init.txt", "path/to/swarm_init.txt", "normal content")

        val result = decoder.decode(listOf(patch))

        assertTrue(result.isSuspicious)
        assertEquals("NamingProtocol", result.protocolName)
        assertTrue(result.evidence.any { it.contains("swarm_init") })
    }

    @Test
    fun testCleanPatch() {
        val decoder = StigmergicProtocolDecoder()
        val patch = PatchData("normal_file.kt", "path/normal_file.kt", "fun main() {}")

        val result = decoder.decode(listOf(patch))

        assertFalse(result.isSuspicious)
        assertEquals(null, result.protocolName)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun testLexicalTokenDetection() {
        val decoder = StigmergicProtocolDecoder()
        val patch = PatchData("normal.txt", "normal.txt", "some very unusualToken in the text")

        val historicalTokens = setOf("unusualToken")
        val result = decoder.decode(listOf(patch), historicalTokens)

        assertTrue(result.isSuspicious)
        assertEquals("LexicalProtocol", result.protocolName)
        assertTrue(result.evidence.any { it.contains("unusualToken") })
    }
}
