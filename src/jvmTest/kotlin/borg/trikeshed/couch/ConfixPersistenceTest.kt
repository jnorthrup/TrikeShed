package borg.trikeshed.couch

import borg.trikeshed.parse.confix.roots
import borg.trikeshed.parse.confix.root
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfixPersistenceTest {

    @Test
    fun `document round-trips through confix persistence`() {
        val persistence = ConfixPersistence()
        val doc = Document(
            id = "doc-1",
            fields = listOf(
                Field("name", "Alice"),
                Field("age", "30"),
                Field("active", "true"),
            ),
        )

        persistence.persist(doc)

        // Confix-backed record exists
        val confixDoc = persistence.loadConfixDoc("doc-1")
        assertNotNull(confixDoc, "confix doc must exist after persist")
        assertTrue(confixDoc.root != null, "confix doc must have a root")

        // Round-trip back to Document
        val loaded = persistence.loadDocument("doc-1")
        assertNotNull(loaded)
        assertEquals("doc-1", loaded.id)
        val fieldsByName = loaded.fields.associateBy { it.name }
        assertEquals("Alice", fieldsByName["name"]?.value)
        assertEquals("30", fieldsByName["age"]?.value)
    }

    @Test
    fun `delete removes confix record`() {
        val persistence = ConfixPersistence()
        persistence.persist(Document("doc-2", listOf(Field("k", "v"))))
        assertNotNull(persistence.loadConfixDoc("doc-2"))
        persistence.delete("doc-2")
        assertNull(persistence.loadConfixDoc("doc-2"))
    }

    @Test
    fun `couch store with confix persistence stores and queries`() {
        val persistence = ConfixPersistence()
        val store = CouchStoreFactory.withPersistence(persistence)

        store.put(Document("user:1", listOf(Field("name", "Bob"), Field("role", "admin"))))
        store.put(Document("user:2", listOf(Field("name", "Carol"), Field("role", "user"))))

        // Document is in the store
        val doc = store.get("user:1")
        assertNotNull(doc)
        assertEquals("Bob", doc.fields.first { it.name == "name" }.value)

        // Confix-backed record is persisted underneath
        val confixRec = persistence.loadConfixDoc("user:1")
        assertNotNull(confixRec)
        assertEquals(2, store.size)

        // ids reflect both documents
        assertTrue(persistence.ids().contains("user:1"))
        assertTrue(persistence.ids().contains("user:2"))
    }
}
