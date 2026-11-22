package borg.trikeshed.reactor.xet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectors: BLAKE3 values come from the `blake3` Python package (input byte i = i % 251; keyed tests use
 * key = bytes 0..31). The 300000-byte stream and its expected values come from a Python prototype that
 * reproduces every hash in huggingface.co/datasets/xet-team/xet-spec-reference-files (796 chunks, xorb,
 * file and range hashes); the same reference files are checked on the JVM in XetReferenceFilesTest.
 */
class XetHashingTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun blake3Vectors() {
        val v = listOf(
            Triple(0, "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", "73492b19995d71cdb1e9d74decc09809eb732f1b00bc95c27cb15f9dd4d6478f"),
            Triple(1, "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213", "d08b45c6b127ee94f3f8527a0b82a5f80be1695a0eaec6022e772c0eb95a7e8b"),
            Triple(1025, "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444", "82223147a9b804a0c3f9a921b8d8aee250d1a51bb76be72152e6d5e8f27349b3"),
            Triple(5000, "ee78d92070de3df1c57c37002abf0a6b1a6589acdeef4d8ffac7cf3d9e8f2836", "3742909929a310dec551d9163c279136d6506f289f75c283c5df101108cd09e5"),
        )
        for ((n, plain, keyed) in v) {
            assertEquals(plain, hex(Blake3.hash(pattern(n))), "hash n=$n")
            assertEquals(keyed, hex(Blake3.keyedHash(key, pattern(n))), "keyed n=$n")
        }
    }

    @Test
    fun apiHexReversesEightByteGroups() {
        val h = ByteArray(32) { it.toByte() }
        assertEquals("07060504030201000f0e0d0c0b0a090817161514131211101f1e1d1c1b1a1918", XetHashing.toApiHex(h))
        assertEquals(h.toList(), XetHashing.fromApiHex(XetHashing.toApiHex(h)).toList())
    }

    @Test
    fun syntheticStream() {
        var x = 0x2545F4914F6CDD1DL
        val d = ByteArray(300000) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            (x and 0xFF).toByte()
        }
        val spans = XetChunker.chunk(d)
        assertEquals(7, spans.size)
        assertEquals(46278, spans[0].length)
        val nodes = XetHashing.chunkNodes(d, spans)
        assertEquals("8fb9d0e57979f01549c4d720b94a09771a7b507ab5d9b2bbe595c88ddc1beb49", XetHashing.toApiHex(nodes[0].hash))
        assertEquals("e1b64d50e67f754f25fc6aa3bf1da009ae231bb14cc0665f3890ce78bf5d1e3c", XetHashing.toApiHex(XetHashing.merkleRoot(nodes)))
        assertEquals("73d8d8ec00e89719d0d93b2a1f4866ec7e1c6d5b6a3979dc2d30cdc7c493a861", XetHashing.toApiHex(XetHashing.fileHash(nodes)))
        val bridge = XetIpfsBridge.bridge(d)
        assertEquals("bafkreidenzynia43pgrsr5va7saiwrnoex2s2rwgdi6tj3vh2jydpaupwi", bridge.chunks[0].cid)
        assertEquals(d.size.toLong(), bridge.chunks.sumOf { it.length.toLong() })
    }

    @Test
    fun casPaths() {
        val id = "118a53328412787fee04011dcf82fdc4acf3a4a1eddec341c910d30a306aaf97"
        assertEquals("/v2/reconstructions/$id", XetCasProtocol.reconstructionPath(id))
        assertEquals("bytes=0-99", XetCasProtocol.rangeHeader(0, 99))
    }
}
