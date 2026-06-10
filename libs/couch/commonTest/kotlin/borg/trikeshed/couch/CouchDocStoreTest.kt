package borg.trikeshed.couch

import borg.trikeshed.parse.confix.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for ConfixDocStore, ViewStore, and CouchPipeline.
 */
class CouchDocStoreTest {

    @Test
    fun `doc store insert and get`() {
        val store = ConfixDocStoreFactory.create()
        val doc = confixDoc("""{"foo":"bar"}""")

        val entry = store.put("doc1", doc)
        assertNotNull(entry)
        assertEquals("doc1", entry!!.id)
        assertNotNull(entry.rev)

        val fetched = store["doc1"]
        assertNotNull(fetched)
        assertEquals(entry.id, fetched!!.id)
    }

    @Test
    fun `doc store update with rev`() {
        val store = ConfixDocStoreFactory.create()
        val doc1 = confixDoc("""{"v":1}""")
        val doc2 = confixDoc("""{"v":2}""")

        val entry1 = store.put("doc1", doc1)
        assertNotNull(entry1)

        // Update without rev → conflict
        val conflict = store.put("doc1", doc2)
        assertNull(conflict)

        // Update with correct rev → success
        val entry2 = store.put("doc1", doc2, entry1!!.rev)
        assertNotNull(entry2)
        assertNotEquals(entry1.rev, entry2!!.rev)
    }

    @Test
    fun `doc store delete`() {
        val store = ConfixDocStoreFactory.create()
        val doc = confixDoc("""{"foo":"bar"}""")

        val entry = store.put("doc1", doc)
        assertNotNull(entry)

        val deleted = store.delete("doc1", entry!!.rev)
        assertTrue(deleted)

        assertNull(store["doc1"])
    }

    @Test
    fun `doc store filter`() {
        val store = ConfixDocStoreFactory.create()
        store.put("a", confixDoc("""{"type":"x"}"""))
        store.put("b", confixDoc("""{"type":"y"}"""))
        store.put("c", confixDoc("""{"type":"x"}"""))

        val filtered = store.filter { entry ->
            entry.doc.value("type") == "x"
        }

        assertEquals(2, filtered.a)
    }

    @Test
    fun `view store map emit`() {
        val store = ConfixDocStoreFactory.create()
        store.put("d1", confixDoc("""{"owner":"A","kind":1}"""))
        store.put("d2", confixDoc("""{"owner":"A","kind":2}"""))
        store.put("d3", confixDoc("""{"owner":"B","kind":1}"""))

        val views = ViewStoreFactory.create(store)

        // Define view by owner
        views.define(
            "by_owner",
            map = { doc, emit ->
                val owner = doc.doc.value("owner") as? String ?: return@define
                emit(owner, doc.id)
            },
            reduce = { _, values -> values.a }
        )

        // Index
        views.reindex()

        // Query
        @Suppress("UNCHECKED_CAST")
        val aView = (views["by_owner"] as? ViewIndex<String, String, Int>)?.forKey("A")
        assertNotNull(aView)
        assertEquals(2, aView!!.count)
    }

    @Test
    fun `view store starts with`() {
        val store = ConfixDocStoreFactory.create()
        store.put("a1", confixDoc("""{"k":"aa"}"""))
        store.put("a2", confixDoc("""{"k":"ab"}"""))
        store.put("b1", confixDoc("""{"k":"ba"}"""))

        val views = ViewStoreFactory.create(store)
        views.define(
            "by_k",
            map = { doc, emit ->
                val k = doc.doc.value("k") as? String ?: return@define
                emit(k, doc.id)
            }
        )
        views.reindex()

        @Suppress("UNCHECKED_CAST")
        val results = (views["by_k"] as? ViewIndex<String, String, Int>)?.startsWith("a")
        assertNotNull(results)
        assertEquals(2, results!!.count)
    }

    @Test
    fun `couch pipeline taxonomy integration`() {
        val store = ConfixDocStoreFactory.create()
        store.put("pkg.Test#run", confixDoc("""{"_id":"pkg.Test#run","symbolName":"pkg.Test.run","ownerType":"pkg.Test","methodOrField":"run","cpIndex":1,"descriptor":"()V","pointcutKind":16,"poolId":1}"""))
        store.put("pkg.Test#field", confixDoc("""{"_id":"pkg.Test#field","symbolName":"pkg.Test.field","ownerType":"pkg.Test","methodOrField":"field","cpIndex":2,"descriptor":"I","pointcutKind":165,"poolId":2}"""))

        // Build pipeline
        val pipeline = CouchPipelineFactory.create()
        pipeline.index()

        // Verify docs
        assertEquals(2, pipeline.store.size)

        // Verify view query
        val byOwner = pipeline.byOwner("pkg.Test")
        assertNotNull(byOwner)
        assertEquals(2, byOwner!!.count)
    }

    @Test
    fun `couch pipeline view by kind`() {
        val store = ConfixDocStoreFactory.create()
        store.put("pkg.Meth#run", confixDoc("""{"_id":"pkg.Meth#run","ownerType":"pkg.Meth","pointcutKind":16}"""))
        store.put("pkg.Meth#f", confixDoc("""{"_id":"pkg.Meth#f","ownerType":"pkg.Meth","pointcutKind":165}"""))

        val pipeline = CouchPipelineFactory.create()
        pipeline.index()

        // Query by pointcut kind (converted to String)
        val byKind = pipeline.byKind(165)
        assertNotNull(byKind)
        assertEquals(1, byKind!!.count)
    }

    @Test
    fun `pipeline state transitions`() {
        val pipeline = CouchPipelineFactory.create()
        assertEquals(CouchPipeline.State.Empty, pipeline.state)

        pipeline.store.put("p.C#m", confixDoc("""{"_id":"p.C#m","ownerType":"p.C"}"""))
        assertEquals(CouchPipeline.State.Empty, pipeline.state)

        pipeline.index()
        assertEquals(CouchPipeline.State.Indexed, pipeline.state)
    }
}
