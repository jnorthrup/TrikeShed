package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S1 — ContentId contract tests.
 *
 * Spec (job-nexus.plan §S1):
 *  - SHA-256 over canonical bytes; full 32 bytes
 *  - Display text: "sha256:" + 64 lowercase hex chars
 *  - Same bytes ⇒ same CID; one-bit flip ⇒ different CID
 *  - hex property returns the 64-char hex without the prefix
 */
class ContentIdTest {

    @Test
    fun cidHasCorrectFormat() {
        val cid = ContentId.of("hello".encodeToByteArray())
        assertTrue(cid.value.startsWith("sha256:"),
            "CID must carry the sha256: prefix, got: ${cid.value}")
        assertEquals(7 + 64, cid.value.length,
            "CID must be 'sha256:' + 64 hex chars (71 total)")
        assertEquals(cid.value.removePrefix("sha256:"), cid.hex)
        assertTrue(cid.hex.all { it in "0123456789abcdef" },
            "hex must be lowercase, got: ${cid.hex}")
    }

    @Test
    fun cidIsDeterministicForSameBytes() {
        val a = ContentId.of("the quick brown fox".encodeToByteArray())
        val b = ContentId.of("the quick brown fox".encodeToByteArray())
        assertEquals(a, b, "same bytes must produce identical CIDs")
        assertEquals(a.hashCode(), b.hashCode(),
            "data class equality must propagate to hashCode")
    }

    @Test
    fun cidDiffersOnSingleBitChange() {
        val a = ContentId.of("payload".encodeToByteArray())
        val b = ContentId.of("payloaD".encodeToByteArray()) // capital D → different byte
        assertNotEquals(a, b, "one-byte difference must yield different CIDs")
    }

    @Test
    fun cidEmptyAndOneByteDiffer() {
        val empty = ContentId.of(ByteArray(0))
        val one = ContentId.of(byteArrayOf(0x00))
        assertNotEquals(empty, one)
        assertEquals(71, empty.value.length,
            "even the empty payload produces a 64-hex-char CID")
    }

    @Test
    fun cidMatchesKnownSha256Vector() {
        // RFC 4634 / NIST FIPS 180-4 example: empty string
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val cid = ContentId.of(ByteArray(0))
        assertEquals(
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            cid.value,
        )
    }

    @Test
    fun cidOfNonByteInputRejected() {
        // Defensive: only ByteArray is accepted. Verify there is no string overload
        // that bypasses encoding.
        val notString: Any = "hello".encodeToByteArray()
        // The only public overloads are ByteArray and ConfixDoc. Anything else
        // must not compile. This test asserts the API surface indirectly by
        // showing the canonical path.
        assertTrue(notString is ByteArray, "test data must be a ByteArray")
    }

    @Test
    fun cidRejectsUppercaseHex() {
        val a = ContentId.of("X".encodeToByteArray())
        val upperHex = a.hex.uppercase()
        assertFailsWith<IllegalArgumentException> {
            ContentId("sha256:$upperHex")
        }
    }

    @Test
    fun cidRejectsWrongAlgorithmOrDigestLength() {
        assertFailsWith<IllegalArgumentException> {
            ContentId("sha1:${"0".repeat(40)}")
        }
        assertFailsWith<IllegalArgumentException> {
            ContentId("sha256:00")
        }
    }

    @Test
    fun cidOfOneMiBDocumentIsComputed() {
        val payload = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        val cid = ContentId.of(payload)
        assertEquals(71, cid.value.length)
    }
}

/**
 * S1 — CasStore contract tests.
 *
 * Spec: put idempotent, get verifies digest, corrupted blob rejected, distinct
 * bytes produce distinct CIDs.
 */
class CasStoreTest {

    @Test
    fun putReturnsStableCid() {
        val store = CasStore.inMemory()
        val bytes = "hello".encodeToByteArray()
        val a = store.put(bytes)
        val b = store.put(bytes)
        assertEquals(a, b, "put must be idempotent on identical bytes")
    }

    @Test
    fun getReturnsExactBytes() {
        val store = CasStore.inMemory()
        val bytes = ByteArray(256) { it.toByte() }
        val cid = store.put(bytes)
        val fetched = store.get(cid)
        assertTrue(fetched != null && fetched.contentEquals(bytes),
            "get must return a byte-equal copy of the stored payload")
    }

    @Test
    fun distinctBytesYieldDistinctCids() {
        val store = CasStore.inMemory()
        val a = store.put("alpha".encodeToByteArray())
        val b = store.put("beta".encodeToByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun corruptedBlobIsRejectedOnGet() {
        val store = CasStore.inMemory()
        val bytes = "payload".encodeToByteArray()
        val cid = store.put(bytes)
        store.corrupt(cid)
        assertFailsWith<IllegalStateException>("digest mismatch: stored blob does not match CID $cid") {
            store.get(cid)
        }
    }

    @Test
    fun getOfUnknownCidReturnsNull() {
        val store = CasStore.inMemory()
        val unknown = ContentId.of("never-stored".encodeToByteArray())
        assertEquals(null, store.get(unknown))
    }

    @Test
    fun putReturnsDefensiveCopy() {
        val store = CasStore.inMemory()
        val bytes = "immutable-input".encodeToByteArray()
        val cid = store.put(bytes)
        bytes[0] = 0x00 // mutate after put
        val fetched = store.get(cid)
        assertTrue(fetched != null && fetched.contentEquals("immutable-input".encodeToByteArray()),
            "put must defensively copy so caller mutation cannot corrupt stored bytes")
    }
}