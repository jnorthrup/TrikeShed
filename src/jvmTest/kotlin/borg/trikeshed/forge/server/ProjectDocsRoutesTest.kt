package borg.trikeshed.forge.server

import borg.trikeshed.parse.reify

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.lcnc.InMemoryProjectCorpus
import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectNodes
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
        fun wire(withCorpus: Boolean = true, projectCorpus: ProjectCorpus? = null): PatchWire {
            val keyMux = KeyMux {}
            val brain = BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = ModelMux(keyMux) {})
            return PatchWire(brain, scopes, attachments = gateway, corpus = if (withCorpus) projectCorpus ?: JvmProjectCorpus(registry, scopes) else null)
        }
        fun folder(): File {
            val dir = File(home, "notes-${System.nanoTime()}").apply { mkdirs() }
            File(dir, "a.md").writeText("# Alpha\n\nthe first note")
            File(dir, "b.md").writeText("# Beta\n\nthe second note")
            File(dir, "c.pdf").writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3))
            return dir
        }
    }

    @Test
    fun sourceAndDisplayedTextRevisionsAreDistinctAndLegacyFieldsRemain(): Unit = runBlocking {
        val corpus = InMemoryProjectCorpus()
        val sourceCid = corpus.put("p", "agreement.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46), "application/pdf")
        val twinId = "agreement.pdf" + ProjectNodes.EXTRACT_SUFFIX
        val extractCid = corpus.put("p", twinId, "extracted text".encodeToByteArray(), "text/markdown")
        val wire = Rig().wire(projectCorpus = corpus)
        suspend fun read() = json(get(wire, "/api/projects/p/docs/agreement.pdf"))
        var result = read()
        var source = result["sourceRevision"] as Map<*, *>
        var displayed = result["displayTextRevision"] as Map<*, *>
        assertEquals(sourceCid, source["cid"])
        assertEquals("agreement.pdf", source["id"])
        assertEquals(extractCid, displayed["cid"])
        assertEquals(twinId, displayed["id"])
        assertEquals(displayed, result["extractionRevision"])
        assertEquals(extractCid, result["cid"], "legacy cid still identifies the returned text")
        assertEquals("application/pdf", result["contentType"])
        assertEquals("extracted text", result["text"])
        assertEquals("extracted text", result["extract"])

        val revisedSourceCid = corpus.put("p", "agreement.pdf", "%PDF changed".encodeToByteArray(), "application/pdf")
        result = read()
        source = result["sourceRevision"] as Map<*, *>
        displayed = result["displayTextRevision"] as Map<*, *>
        assertEquals(revisedSourceCid, source["cid"])
        assertEquals(extractCid, displayed["cid"], "the twin is not asserted to match the revised source")
        val revisedExtractCid = corpus.put("p", twinId, "updated extraction".encodeToByteArray(), "text/markdown")
        result = read()
        assertEquals(revisedSourceCid, (result["sourceRevision"] as Map<*, *>)["cid"])
        assertEquals(revisedExtractCid, (result["displayTextRevision"] as Map<*, *>)["cid"])

        corpus.put("p", "note.md", "# source".encodeToByteArray(), "text/markdown")
        val textResult = json(get(wire, "/api/projects/p/docs/note.md"))
        assertEquals("note.md", (textResult["sourceRevision"] as Map<*, *>)["id"])
        assertEquals((textResult["sourceRevision"] as Map<*, *>)["cid"], (textResult["displayTextRevision"] as Map<*, *>)["cid"])
        assertNull(textResult["extractionRevision"])
    }

    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!

    @Suppress("UNCHECKED_CAST")
    private fun json(r: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = reify(r.body) as Map<String, Any?>

    @Test
    fun encodedPrimaryProjectNameAndDocumentIdReachTheActualRoute(): Unit = runBlocking {
        val rig = Rig()
        rig.scopes.registerPrimary(File(rig.home, "Docs and law+&"), 1)
        val project = rig.scopes.list().single().name
        assertEquals("docs and law+&", project)
        val corpus = InMemoryProjectCorpus()
        corpus.put(project, "folder/agreement+1.txt", "text".encodeToByteArray(), "text/plain")
        val wire = rig.wire(projectCorpus = corpus)
        val listing = get(wire, "/api/projects/docs%20and%20law%2B%26/docs")
        assertEquals(200, listing.status, listing.body)
        assertEquals(project, json(listing)["project"])
        assertEquals(1, (json(listing)["count"] as Number).toInt())
        val read = get(wire, "/api/projects/docs%20and%20law%2B%26/docs/folder%2Fagreement%2B1.txt")
        assertEquals(200, read.status, read.body)
        assertEquals("text", json(read)["text"])
        assertEquals("folder/agreement+1.txt", json(read)["id"])
        assertEquals(project, json(get(wire, "/api/projects/docs%20and%20law+%26/docs"))["project"], "a literal plus stays a plus")
        assertEquals(400, get(wire, "/api/projects/%ZZ/docs").status)
    }

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
        assertEquals(docs[0]["cid"], (doc["sourceRevision"] as Map<*, *>)["cid"])
        assertEquals(doc["cid"], (doc["displayTextRevision"] as Map<*, *>)["cid"])
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

    @Test
    fun selectedTextIngestUsesReviewHookWithoutAProviderOrMiner(): Unit = runBlocking {
        val rig = Rig()
        val scope = rig.scopes.mount(rig.folder().absolutePath)
        val calls = mutableListOf<Pair<String, String>>()
        val wire = rig.wire()
        val path = "/api/projects/${scope.name}/ingest/a%2Emd"
        suspend fun post() = wire.route("POST", path, "POST $path HTTP/1.1\r\n\r\n{}", null)!!
        assertEquals(400, post().status, "a missing review hook must not report a queued document")
        rig.scopes.onTextForReview = { selected -> calls.add(selected.project to selected.id) }
        val result = post()
        assertEquals(202, result.status, result.body)
        assertEquals(listOf(scope.name to "a.md"), calls)
        assertEquals("queued", json(result)["verdict"])
        assertNull(json(result)["decisionProvider"])
    }
}
