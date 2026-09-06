package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import keymux.KeyMux
import modelmux.ModelMux
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The document surface's routes read the same corpus the project legos read. */
class ProjectDocsRoutesTest {

    private class Rig {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val registry = ProjectDbRegistry("trikeshed")
        val home = File(System.getProperty("java.io.tmpdir"), "docs-routes-${System.nanoTime()}").apply { mkdirs() }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couch))), cas, null, projectDbs = registry, ledgerFile = File(home, "mount-ledger.tsv"), filesRoot = File(home, "files"))
        fun wire(withCorpus: Boolean = true): PatchWire {
            val keyMux = KeyMux {}
            val brain = BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = ModelMux(keyMux) {})
            return PatchWire(brain, scopes, attachments = gateway, corpus = if (withCorpus) JvmProjectCorpus(registry, scopes) else null)
        }
        fun folder(): File {
            val dir = File(home, "notes-${System.nanoTime()}").apply { mkdirs() }
            File(dir, "a.md").writeText("# Alpha\n\nthe first note")
            File(dir, "b.md").writeText("# Beta\n\nthe second note")
            File(dir, "c.pdf").writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3))
            return dir
        }
    }

    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!

    @Suppress("UNCHECKED_CAST")
    private fun json(r: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = JsonSupport.parse(r.body) as Map<String, Any?>

    @Test
    fun listsReadsAndRefusesThroughTheRoutes(): Unit = runBlocking {
        val rig = Rig()
        val scope = rig.scopes.mount(rig.folder().absolutePath)
        val wire = rig.wire()

        val listing = get(wire, "/api/projects/${scope.name}/docs?glob=*.md&limit=10")
        assertEquals(200, listing.status, listing.body)
        val docs = json(listing)["docs"] as List<Map<String, Any?>>
        assertEquals(listOf("a.md", "b.md"), docs.map { it["id"] })
        assertEquals(ContentId.of("# Alpha\n\nthe first note".encodeToByteArray()).value, docs[0]["cid"])
        assertEquals(2, (json(listing)["count"] as Number).toInt())

        val everything = json(get(wire, "/api/projects/${scope.name}/docs"))["docs"] as List<Map<String, Any?>>
        assertEquals(listOf("a.md", "b.md", "c.pdf"), everything.map { it["id"] })

        val read = get(wire, "/api/projects/${scope.name}/docs/a.md")
        assertEquals(200, read.status, read.body)
        val doc = json(read)
        assertEquals("a.md", doc["id"]); assertEquals("# Alpha\n\nthe first note", doc["text"])
        assertEquals(docs[0]["cid"], doc["cid"]); assertEquals("text/markdown", doc["contentType"])
        assertTrue((doc["seq"] as Number).toLong() >= 0)
        assertNull(doc["extract"], "no twin was mined")

        val encoded = get(wire, "/api/projects/${scope.name}/docs/b%2Emd")
        assertEquals("b.md", json(encoded)["id"], "the id is URL-decoded")

        val binary = get(wire, "/api/projects/${scope.name}/docs/c.pdf")
        assertEquals(415, binary.status, binary.body)
        assertEquals("not_text", json(binary)["error"])

        assertEquals(404, get(wire, "/api/projects/${scope.name}/docs/nosuch.md").status)
        assertEquals(200, get(wire, "/api/projects/nosuch/docs").status, "an unknown project lists nothing rather than failing")
        assertEquals(0, (json(get(wire, "/api/projects/nosuch/docs"))["count"] as Number).toInt())

        assertEquals(503, get(rig.wire(withCorpus = false), "/api/projects/${scope.name}/docs").status, "unwired corpus says so")
    }
}
