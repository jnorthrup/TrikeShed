package borg.trikeshed.couch

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CouchProjectionTest {

    @Test
    fun testCreateUpdateDeleteRoundTrip() {
        val store = CouchStoreFactory.inMemory()

        val doc1 = Document("doc1", listOf(Field("name", "Alice")))
        assertTrue(store.put(doc1), "Should return true for initial insert")

        val retrieved1 = store.get("doc1")
        assertNotNull(retrieved1)
        assertEquals("Alice", retrieved1.fields[0].value)

        val doc1Update = Document("doc1", listOf(Field("name", "Bob")))
        assertFalse(store.put(doc1Update), "Should return false for update")

        val retrieved2 = store.get("doc1")
        assertNotNull(retrieved2)
        assertEquals("Bob", retrieved2.fields[0].value)

        assertTrue(store.delete("doc1"))
        assertNull(store.get("doc1"))

        assertFalse(store.delete("doc1"), "Should return false for deleting already deleted doc")
    }

    @Test
    fun testStaleRevisionRejection() {
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()
        val ingress = CouchStoreFactory.SyncTestIngress(head, changes, NoOpPersistence)
        val store = CouchStore(ingress, head, changes)

        val doc = Document("doc2", listOf(Field("age", 30)))
        assertTrue(ingress.putIntent(doc, null), "Insert should succeed")

        val rev = head.getRev("doc2")
        assertNotNull(rev)

        val docUpdate = Document("doc2", listOf(Field("age", 31)))
        assertFalse(ingress.putIntent(docUpdate, "wrong-rev"), "Update with wrong expected rev should fail")

        assertEquals(30, store.get("doc2")?.fields?.get(0)?.value)

        assertFalse(ingress.putIntent(docUpdate, rev), "Update with correct rev should succeed but return false for update")
        assertEquals(31, store.get("doc2")?.fields?.get(0)?.value)
    }

    @Test
    fun testTombstoneHistory() {
        val store = CouchStoreFactory.inMemory()

        val doc = Document("doc3", listOf(Field("color", "red")))
        store.put(doc)
        store.delete("doc3")

        val frames = store.changes.series()
        assertEquals(2, frames.size)

        val delFrame = frames[1]
        assertTrue(delFrame.deleted)
        assertEquals("doc3", delFrame.docId)
        assertNull(delFrame.doc)
    }

    @Test
    fun testNoPreCommitVisibility() {
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()

        // Use an ingress that doesn't immediately commit to projection
        val ingress = object : CouchIngress {
            override fun putIntent(doc: Document, expectedRev: String?): Boolean {
                return true
            }

            override fun deleteIntent(docId: String, expectedRev: String?): Boolean {
                return true
            }
        }

        val store = CouchStore(ingress, head, changes)
        val doc = Document("doc4", listOf())

        store.put(doc)

        assertNull(store.get("doc4"), "Should not be visible because it hasn't been committed to head projection")
    }

    @Test
    fun testChangesResumeAndReplayEquivalence() {
        val store = CouchStoreFactory.inMemory()

        store.put(Document("a", emptyList()))
        store.put(Document("b", emptyList()))

        var count = 0
        val cancel = store.changes.subscribe { twin ->
            count = twin.a.size
        }

        store.put(Document("c", emptyList()))
        assertEquals(3, count)
        cancel()

        store.put(Document("d", emptyList()))
        assertEquals(3, count, "Should not update after cancel")

        val frames = store.changes.series()
        assertEquals(4, frames.size)
        assertEquals("a", frames[0].docId)
        assertEquals("b", frames[1].docId)
        assertEquals("c", frames[2].docId)
        assertEquals("d", frames[3].docId)
    }
}
