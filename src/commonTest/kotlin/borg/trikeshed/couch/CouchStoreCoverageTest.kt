package borg.trikeshed.couch

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.Series
import kotlin.test.*

class CouchStoreCoverageTest {


    @Test
    fun testCreateUpdateDeleteRoundTrip() {
        val store = CouchStoreFactory.inMemory()
        
        val doc1 = Document("doc1", listOf(Field("name", "Alice")))
        assertTrue(store.put(doc1), "Initial put should return true")
        
        val retrieved1 = store.get("doc1")
        assertNotNull(retrieved1)
        assertEquals("Alice", retrieved1.fields.first { it.name == "name" }.value)
        
        val rev1 = store.head.getRev("doc1")
        assertNotNull(rev1)
        
        val doc1Update = Document("doc1", listOf(Field("name", "Bob")))
        val updateResult = store.put(doc1Update, rev1)
        assertTrue(updateResult, "Update put should return true")
        
        val retrieved2 = store.get("doc1")
        assertNotNull(retrieved2)
        assertEquals("Bob", retrieved2.fields.first { it.name == "name" }.value)
        
        val rev2 = store.head.getRev("doc1")
        assertNotNull(rev2)
        
        assertTrue(store.delete("doc1", rev2), "Delete should return true")
        assertNull(store.get("doc1"), "Document should be null after delete")
        assertFalse(store.contains("doc1"), "Store should not contain deleted doc")
    }


    @Test
    fun testStaleRevisionRejection() {
        val store = CouchStoreFactory.inMemory()
        
        val doc1 = Document("doc1", listOf(Field("name", "Alice")))
        assertTrue(store.put(doc1), "Initial put should succeed")
        
        val rev = store.head.getRev("doc1")
        assertNotNull(rev)
        
        val doc1Update = Document("doc1", listOf(Field("name", "Bob")))
        assertFalse(store.put(doc1Update, "bad-rev"), "Update with bad rev should fail")
        assertFalse(store.put(doc1Update), "Update with null rev on existing doc should fail")
        assertTrue(store.put(doc1Update, rev), "Update with correct rev should succeed")
    }


    @Test
    fun testTombstoneHistory() {
        val store = CouchStoreFactory.inMemory()
        
        val doc1 = Document("doc1", listOf(Field("name", "Alice")))
        store.put(doc1)
        
        val rev1 = store.head.getRev("doc1")
        assertNotNull(rev1)
        
        store.delete("doc1", rev1)
        
        assertNull(store.get("doc1"))
        assertFalse(store.contains("doc1"))
        assertTrue(store.head.isDeleted("doc1"))
        
        val revAfterDelete = store.head.getRev("doc1")
        assertNotNull(revAfterDelete)
        assertNotEquals(rev1, revAfterDelete)
        assertTrue(revAfterDelete.endsWith("-deleted"))
    }


    @Test
    fun testNoPreCommitVisibility() {
        // Asserting that changes only reflect successfully committed intents
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()
        
        var commitCount = 0
        val ingress = ProductionCouchIngress(
            head = head,
            commitBoundary = { frame ->
                head.applyCommit(frame)
                changes.applyCommit(frame)
                commitCount++
            },
            contentIdFn = { doc -> borg.trikeshed.job.ContentId.of(doc.id.encodeToByteArray()) }
        )
        
        val doc1 = Document("doc1", listOf())
        ingress.putIntent(doc1, null)
        assertEquals(1, commitCount)
        
        // Stale update intent should fail and NOT trigger commitBoundary
        val intentResult = ingress.putIntent(doc1, "wrong-rev")
        assertFalse(intentResult)
        assertEquals(1, commitCount)
    }


    @Test
    fun testContainsCheck() {
        val store = CouchStoreFactory.inMemory()
        assertFalse(store.contains("doc1"))
        store.put(Document("doc1", listOf()))
        assertTrue(store.contains("doc1"))
    }


    @Test
    fun testSubscriptionReceivesMutations() {
        val store = CouchStoreFactory.inMemory()
        
        val events = mutableListOf<CouchStore.MutationEvent>()
        val cancel = store.subscribeMutations { events.add(it) }
        
        store.put(Document("doc1", listOf(Field("name", "Alice"))))
        
        val rev1 = store.head.getRev("doc1")!!
        store.put(Document("doc1", listOf(Field("name", "Bob"))), rev1)
        
        val rev2 = store.head.getRev("doc1")!!
        store.delete("doc1", rev2)
        
        assertEquals(3, events.size)
        assertTrue(events[0] is CouchStore.MutationEvent.Inserted)
        assertTrue(events[1] is CouchStore.MutationEvent.Updated)
        assertTrue(events[2] is CouchStore.MutationEvent.Deleted)
        
        cancel()
    }


    @Test
    fun testChangesResumeAndReplayEquivalence() {
        val store = CouchStoreFactory.inMemory()
        
        store.put(Document("doc1", listOf(Field("name", "Alice"))))
        store.put(Document("doc2", listOf(Field("name", "Bob"))))
        val rev2 = store.head.getRev("doc2")!!
        store.delete("doc2", rev2)
        
        val frames = store.changes.series()
        assertEquals(3, frames.a)
        
        val afterSeq0 = store.changes.afterSequence(frames.b(0).sequence)
        assertEquals(2, afterSeq0.a)
        assertEquals("doc2", afterSeq0.b(0).docId)
        assertFalse(afterSeq0.b(0).deleted)
        assertEquals("doc2", afterSeq0.b(1).docId)
        assertTrue(afterSeq0.b(1).deleted)
    }


    @Test
    fun testQueryAllReturnsCursor() {
        val store = CouchStoreFactory.inMemory()
        
        store.put(Document("doc1", listOf(Field("name", "Alice"))))
        store.put(Document("doc2", listOf(Field("name", "Bob"))))
        
        val result = store.query()
        assertEquals(2L, result.totalCount)
        assertEquals(2, result.cursor.a)
        
        val row0 = result.cursor.b(0)
        assertEquals("doc1", row0.b(0).a) // _id
        assertEquals("Alice", row0.b(1).a) // name
    }


    @Test
    fun testIdsReturnsAllDocumentIds() {
        val store = CouchStoreFactory.inMemory()
        store.put(Document("doc1", listOf()))
        store.put(Document("doc2", listOf()))
        
        val ids = store.ids()
        assertEquals(2, ids.a)
        val idList = mutableListOf<String>()
        for (i in 0 until ids.a) {
            idList.add(ids.b(i))
        }
        assertTrue(idList.contains("doc1"))
        assertTrue(idList.contains("doc2"))
    }




    @Test
    fun testFlushAndDrainAreNoOpForInMemory() {
        val store = CouchStoreFactory.inMemory()
        store.flush()
        store.drain()
        store.close()
    }



    @Test
    fun testViewServerExecuteStoreUsesQueryCursorPath() {
        val store = CouchStoreFactory.inMemory()
        store.put(Document("doc1", listOf(Field("name", "Alice"), Field("age", 30))))
        store.put(Document("doc2", listOf(Field("name", "Bob"), Field("age", 40))))
        
        val viewDef = ViewDefinition(ddoc = "test", viewName = "testView", mapFn = MapFunction.Emit(KeyExpr.DocField("name"), ValueExpr.DocField("age")))
        
        val viewServer = ViewServer()
        val result = viewServer.execute(viewDef, store)
        
        assertEquals(2, result.rows.a)
        
        var foundAlice = false
        var foundBob = false
        for (i in 0 until result.rows.a) {
            val row = result.rows.b(i)
            if (row.key == "Alice") {
                assertEquals(30, row.value)
                foundAlice = true
            }
            if (row.key == "Bob") {
                assertEquals(40, row.value)
                foundBob = true
            }
        }
        assertTrue(foundAlice)
        assertTrue(foundBob)
    }
}
