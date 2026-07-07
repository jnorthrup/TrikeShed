package borg.trikeshed.couch

import org.junit.Assert.*
import org.junit.Test

class CouchStoreTest {

    @Test
    fun `put and get document`() {
        val store = CouchStoreFactory.inMemory()

        val doc = Document(
            id = "doc-1",
            fields = listOf(
                Field("name", "test"),
                Field("value", 42),
                Field("active", true)
            )
        )

        val inserted = store.put(doc)
        assertTrue(inserted)
        assertEquals(1, store.size)

        val retrieved = store.get("doc-1")
        assertNotNull(retrieved)
        assertEquals("doc-1", retrieved!!.id)
        assertEquals(3, retrieved.fields.size)
        assertEquals("test", retrieved.fields.find { it.name == "name" }?.value)
    }

    @Test
    fun `update existing document`() {
        val store = CouchStoreFactory.inMemory()

        val doc1 = Document("doc-1", listOf(Field("count", 1)))
        store.put(doc1)

        val doc2 = Document("doc-1", listOf(Field("count", 2)))
        val inserted = store.put(doc2)

        assertFalse(inserted) // updated, not inserted
        assertEquals(1, store.size)

        val retrieved = store.get("doc-1")!!
        assertEquals(2, retrieved.fields.find { it.name == "count" }?.value)
    }

    @Test
    fun `delete document`() {
        val store = CouchStoreFactory.inMemory()

        val doc = Document("doc-1", listOf(Field("x", 1)))
        store.put(doc)
        assertEquals(1, store.size)

        val deleted = store.delete("doc-1")
        assertTrue(deleted)
        assertEquals(0, store.size)
        assertNull(store.get("doc-1"))

        // Delete non-existent
        assertFalse(store.delete("doc-1"))
    }

    @Test
    fun `contains check`() {
        val store = CouchStoreFactory.inMemory()

        assertFalse(store.contains("doc-1"))

        store.put(Document("doc-1", listOf(Field("x", 1))))
        assertTrue(store.contains("doc-1"))
    }

    @Test
    fun `ids returns all document ids`() {
        val store = CouchStoreFactory.inMemory()

        store.put(Document("a", listOf(Field("x", 1))))
        store.put(Document("b", listOf(Field("x", 2))))
        store.put(Document("c", listOf(Field("x", 3))))

        val idSeries = store.ids()
        val ids = (0 until idSeries.a).map { idSeries.b(it) }.toSet()
        assertEquals(setOf("a", "b", "c"), ids)
    }

    @Test
    fun `query all returns cursor`() {
        val store = CouchStoreFactory.inMemory()

        store.put(Document("doc-1", listOf(Field("name", "a"), Field("v", 1))))
        store.put(Document("doc-2", listOf(Field("name", "b"), Field("v", 2))))
        store.put(Document("doc-3", listOf(Field("name", "c"), Field("v", 3))))

        val result = store.query()
        assertNotNull(result.cursor)
        assertEquals(3, result.totalCount)
    }

    @Test
    fun `subscription receives mutations`() {
        val store = CouchStoreFactory.inMemory()
        val events = mutableListOf<CouchStore.MutationEvent>()

        val cancel = store.subscribeMutations { events.add(it) }

        store.put(Document("doc-1", listOf(Field("x", 1))))
        store.put(Document("doc-2", listOf(Field("x", 2))))
        store.delete("doc-1")

        assertEquals(3, events.size)
        assertTrue(events[0] is CouchStore.MutationEvent.Inserted)
        assertTrue(events[1] is CouchStore.MutationEvent.Inserted)
        assertTrue(events[2] is CouchStore.MutationEvent.Deleted)

        // Unsubscribe
        cancel()
        store.put(Document("doc-3", listOf(Field("x", 3))))
        assertEquals(3, events.size) // no new events
    }

    @Test
    fun `flush and drain are no-op for in-memory`() {
        val store = CouchStoreFactory.inMemory()

        store.put(Document("doc-1", listOf(Field("x", 1))))
        store.flush()  // should not throw
        store.drain()  // should not throw
        store.close()  // should not throw

        assertEquals(1, store.size)
    }
}
