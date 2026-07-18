package borg.trikeshed.forge

import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path

class ConfixLocalStoreTest {

    private val tempFolder: Path = Files.createTempDirectory("confix-local-store-test-")

    private fun newStore(): ConfixLocalStore {
        return ConfixLocalStore(project = "test-proj", instance = "test-1", baseDir = tempFolder)
    }

    @Test
    fun `put and load confix doc`() {
        val store = newStore()

        store.put("user:1", mapOf("name" to "Alice", "age" to 30, "active" to true))

        val doc = store.loadConfixDoc("user:1")
        assertNotNull(doc, "confix doc must exist after put")
        val loaded = store.loadAsJson("user:1")
        assertNotNull(loaded, "json reification must work")
    }

    @Test
    fun `load as json reifies fields`() {
        val store = newStore()

        store.put("user:1", mapOf("name" to "Bob", "role" to "admin"))

        val json = store.loadAsJson("user:1")
        assertNotNull(json)
        val idField = json!!["_id"]?.toString()
        assertTrue(idField?.contains("user:1") == true, "_id must be present in JSON reification")
    }

    @Test
    fun `load as yaml reifies to indentation mapping`() {
        val store = newStore()

        store.put("user:1", mapOf("name" to "Carol"))

        val yaml = store.loadAsYaml("user:1")
        assertNotNull(yaml)
        assertTrue(yaml!!.contains("name:"), "YAML must contain field name")
    }

    @Test
    fun `delete removes document`() {
        val store = newStore()

        store.put("doc:1", mapOf("x" to 1))
        assertEquals(1L, store.count())
        assertTrue(store.delete("doc:1"))
        assertFalse(store.delete("doc:1"))
        assertEquals(0L, store.count())
    }

    @Test
    fun `all doc ids lists documents`() {
        val store = newStore()

        store.put("a", mapOf("v" to 1))
        store.put("b", mapOf("v" to 2))
        store.put("c", mapOf("v" to 3))

        val ids = store.allDocIds()
        assertEquals(3, ids.size)
        assertTrue(ids.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `query by field returns matching docs`() {
        val store = newStore()

        store.put("doc:1", mapOf("type" to "A", "v" to 1))
        store.put("doc:2", mapOf("type" to "B", "v" to 2))
        store.put("doc:3", mapOf("type" to "A", "v" to 3))

        val typeA = store.findByField("type", "A")
        assertEquals(2, typeA.size)
        assertTrue(typeA.containsAll(listOf("doc:1", "doc:3")))
    }

    @Test
    fun `directory layout follows project instance db id pattern`() {
        val store = newStore()

        store.put("doc:1", mapOf("x" to 1))

        val expectedDir = tempFolder
            .resolve("test-proj")
            .resolve("test-1")
            .resolve("db")
            .resolve("id")
        assertTrue(java.nio.file.Files.exists(expectedDir), "db/id directory must exist")
        val files = java.nio.file.Files.list(expectedDir).use { it.toList() }
        assertTrue(files.any { it.fileName.toString().endsWith(".json") }, "must have .json files")
    }
}
