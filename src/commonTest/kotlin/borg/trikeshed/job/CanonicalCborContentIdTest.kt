package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C02 RED — ContentId must be SHA-256 over CANONICAL CBOR, not raw bytes.
 *
 * The plan: "CAS uses full SHA-256 over canonical CBOR and verifies the digest on read."
 * "Canonical CBOR" means: sorted map keys, definite-length, minimal encoding.
 * Same logical content → same CBOR bytes → same CID, regardless of input JSON whitespace/key order.
 */
class CanonicalCborContentIdTest {

    @Test
    fun sameLogicalContentProducesSameCidRegardlessOfKeyOrder() {
        val docA = ConfixDoc.parse("""{"jobId":"j-1","operation":"submit"}""".encodeToByteArray())
        val docB = ConfixDoc.parse("""{"operation":"submit","jobId":"j-1"}""".encodeToByteArray())

        val cidA = ContentId.of(docA)
        val cidB = ContentId.of(docB)

        assertEquals(cidA, cidB,
            "canonical CBOR must produce same CID regardless of JSON key order")
    }

    @Test
    fun sameLogicalContentProducesSameCidRegardlessOfWhitespace() {
        val docA = ConfixDoc.parse("""{"jobId":"j-1","operation":"submit"}""".encodeToByteArray())
        val docB = ConfixDoc.parse("""
            {
              "jobId": "j-1",
              "operation": "submit"
            }
        """.trimIndent().encodeToByteArray())

        assertEquals(ContentId.of(docA), ContentId.of(docB),
            "canonical CBOR must produce same CID regardless of JSON whitespace")
    }

    @Test
    fun canonicalCborUsesDefiniteLengthAndSortedKeys() {
        val doc = ConfixDoc.parse("""{"b":2,"a":1}""".encodeToByteArray())
        val cborBytes = CanonicalCbor.encode(doc)

        // CBOR map: major type 5 (map), definite length.
        // First byte: 0xa2 = map with 2 entries (0xa0 + count).
        assertEquals(0xa2.toByte(), cborBytes[0],
            "canonical CBOR must use definite-length map")

        // Keys must be sorted: "a" before "b" in the byte stream.
        val keyA = "a".encodeToByteArray()
        val keyB = "b".encodeToByteArray()
        val posA = indexOf(cborBytes, keyA)
        val posB = indexOf(cborBytes, keyB)
        assertTrue(posA >= 0 && posB >= 0, "both keys must be present")
        assertTrue(posA < posB, "canonical CBOR must sort map keys: 'a' must come before 'b'")
    }

    @Test
    fun casVerifiesDigestOnRead() {
        val cas = CasStore.inMemory()
        val doc = ConfixDoc.parse("""{"jobId":"j-1"}""".encodeToByteArray())
        val cid = cas.put(doc)
        val read = cas.get(cid)
        assertNotNull(read)
        // The returned bytes must canonicalize to the same CID
        assertEquals(cid, ContentId.of(read), "CAS read must verify digest")
    }

    @Test
    fun casRejectsCorruptedBlob() {
        val cas = CasStore.inMemory()
        val doc = ConfixDoc.parse("""{"jobId":"j-1"}""".encodeToByteArray())
        val cid = cas.put(doc)
        cas.corrupt(cid)

        try {
            cas.get(cid)
            fail("CAS must reject corrupted blob on read")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("digest") || e.message!!.contains("mismatch") || e.message!!.contains("corrupt"))
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun assertNotNull(read: Any?) {
        kotlin.test.assertNotNull(read)
    }
}
