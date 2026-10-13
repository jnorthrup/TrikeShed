package borg.trikeshed.couch

import kotlin.test.*

class CouchHeadProjectionRetentionTest {
    @Test fun revisionsReleaseUnreferencedFieldValues() {
        val head = CouchHeadProjection()
        val old = mapOf("text" to "old")
        head.applyCommit(CouchCommittedFrame(0, "first", "first-0", false,
            Document("first", listOf(Field("body", old), Field("legacy", "old field")))))
        head.applyCommit(CouchCommittedFrame(1, "second", "second-0", false,
            Document("second", listOf(Field("body", old)))))
        var current = old
        for (revision in 1..100) {
            current = mapOf("text" to "revision-$revision")
            head.applyCommit(CouchCommittedFrame(revision + 1L, "first", "first-$revision", false,
                Document("first", listOf(Field("body", current)))))
        }
        assertEquals(1L, head.query("body", old).totalCount)
        assertEquals(1L, head.query("body", current).totalCount)
        // Inspect retention without exposing the projection's implementation as a public API.
        val field = CouchHeadProjection::class.java.getDeclaredField("fieldIndex").apply { isAccessible = true }
        val index = field.get(head) as Map<*, *>
        assertEquals(setOf("body"), index.keys)
        assertEquals(setOf(old, current), (index["body"] as Map<*, *>).keys)
        head.applyCommit(CouchCommittedFrame(102, "second", "second-deleted", true, null))
        assertEquals(setOf(current), (index["body"] as Map<*, *>).keys)
        head.applyCommit(CouchCommittedFrame(103, "first", "first-deleted", true, null))
        assertTrue(index.isEmpty(), "No field-value bucket may retain the deleted documents' payloads")
    }
}
