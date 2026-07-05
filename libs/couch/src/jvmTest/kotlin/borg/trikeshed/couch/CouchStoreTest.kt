package borg.trikeshed.couch

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
    @Disabled("TODO(couch-query): query(field,value) currently returns an empty cursor/count; implement field index before enabling")
    fun `query by field value`() {
        val store = CouchStoreFactory.inMemory()
        
        store.put(Document("doc-1", listOf(Field("type", "A"), Field("v", 1))))
        store.put(Document("doc-2", listOf(Field("type", "B"), Field("v", 2))))
        store.put(Document("doc-3", listOf(Field("type", "A"), Field("v", 3))))
        
        val result = store.query("type", "A")
        assertEquals(2, result.totalCount)
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

    @Test
    @Disabled("TODO(couch-persistence): Field.value is Any/@Contextual and JsonFilePersistence lacks a serializer module for arbitrary values")
    fun `json file persistence round-trips`(@TempDir tempDir: File) {
        val file = File(tempDir, "store.json")
        val store = CouchStoreFactory.withJsonFile(file)
        
        store.put(Document("doc-1", listOf(Field("name", "test"), Field("value", 123))))
        store.put(Document("doc-2", listOf(Field("name", "other"), Field("value", 456))))
        store.close()
        
        // Reopen and verify
        val store2 = CouchStoreFactory.withJsonFile(file)
        assertEquals(2, store2.size)
        assertEquals("test", store2.get("doc-1")!!.fields.find { it.name == "name" }?.value)
        assertEquals(456, store2.get("doc-2")!!.fields.find { it.name == "value" }?.value)
    }

    @Test
    @Disabled("TODO(couch-query): query() currently returns an empty cursor; implement document cursor projection before enabling reconstruction proof")
    fun `cursor to documents reconstruction`() {
        val store = CouchStoreFactory.inMemory()
        
        store.put(Document("doc-1", listOf(Field("name", "test"), Field("v", 1))))
        store.put(Document("doc-2", listOf(Field("name", "other"), Field("v", 2))))
        
        val result = store.query()
        val docs = result.cursor.toDocuments()
        
        assertEquals(2, docs.a)
    }
}