package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon's corpus over a real mounted folder: metadata listing, text reads, and a revision moving the cid. */
class ProjectCorpusJvmTest {

    private fun rig(): Pair<ProjectScopes, ProjectDbRegistry> {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val bridge = CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couchStore)))
        val registry = ProjectDbRegistry("trikeshed")
        val home = File(System.getProperty("java.io.tmpdir"), "corpus-home-${System.nanoTime()}").apply { mkdirs() }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null, projectDbs = registry, ledgerFile = File(home, "mount-ledger.tsv"), filesRoot = File(home, "files"))
        return scopes to registry
    }

    private fun folder(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "corpus-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "a.md").writeText("alpha")
        File(dir, "b.md").writeText("beta")
        File(dir, "c.pdf").writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3))
        return dir
    }

    @Test
    fun listsReadsAndSeesARevisionMoveTheCid(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.mount(folder().absolutePath)
        val corpus = JvmProjectCorpus(registry, scopes)
        val refs = corpus.projects()
        assertTrue(refs.any { it.name == scope.name && it.docs >= 3 }, "$refs")
        val md = corpus.docs(scope.name, glob = "*.md")
        assertEquals(listOf("a.md", "b.md"), md.map { it.id })
        assertEquals(ContentId.of("alpha".encodeToByteArray()).value, md[0].cid)
        // The store numbers commits from 0; every mounted document carries its own commit sequence.
        assertTrue(md.all { it.seq >= 0 } && md.map { it.seq }.distinct().size == md.size, "each mounted document carries its own store commit sequence: $md")
        val text = corpus.read(scope.name, "a.md")!!
        assertEquals("alpha", text.text); assertEquals(md[0].cid, text.cid)
        assertNull(corpus.read(scope.name, "c.pdf"), "a PDF is not read as text")
        assertNull(corpus.read(scope.name, "nosuch.md"))
        val before = md[0]
        scopes.uploadPut(scope.name, "a.md", "alpha changed".encodeToByteArray())
        val after = corpus.docs(scope.name, glob = "a.md").single()
        assertNotEquals(before.cid, after.cid); assertNotEquals(before.rev, after.rev)
        assertTrue(after.seq > before.seq, "a revision moves the commit sequence forward: ${before.seq} -> ${after.seq}")
        assertEquals("alpha changed", corpus.read(scope.name, "a.md")!!.text)
        val docs = ProjectNodes.registry(corpus).getValue(ProjectNodes.DOCS)
        val out = docs.run(borg.trikeshed.lcnc.LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to scope.name, "glob" to "*.md")), emptyMap())
        assertEquals(2, out["count"])
    }

    @Test
    fun pdfExtractReusesOnlyTheCompletionBoundToItsOriginalRevision(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("pdf-marker")
        scopes.uploadPut(scope.name, "book.pdf", "%PDF-fixture".encodeToByteArray())
        scopes.uploadPut(scope.name, "book.pdf.extract.md", "retained extraction".encodeToByteArray())
        scopes.uploadPut(scope.name, "book.pdf.footnotes.tsv", "page\ttext\n1\tnote".encodeToByteArray())
        completion(scopes, registry, scope.name, "book.pdf", "retained extraction")
        val corpus = JvmProjectCorpus(registry, scopes, ingest = { _, _ -> error("completed PDF must not be extracted again") })
        try {
            assertEquals("application/octet-stream", registry.get(scope.name)!!.gateway
                .getAttachmentRef("book.pdf.footnotes.tsv")!!.contentType)
            assertEquals("retained extraction", corpus.extract(scope.name, "book.pdf", 1_000)?.text)
        } finally { scopes.drain() }
    }

    @Test
    fun concurrentPdfExtractsShareOneCompletedExtraction(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("pdf-concurrent")
        scopes.uploadPut(scope.name, "book.pdf", "%PDF-fixture".encodeToByteArray())
        scopes.uploadPut(scope.name, "book.pdf.extract.md", "legacy extraction without notes".encodeToByteArray())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val corpus = JvmProjectCorpus(registry, scopes, ingest = { project, id ->
            calls++
            entered.complete(Unit)
            release.await()
            scopes.uploadPut(project, id + ProjectMiner.FOOTNOTES_SUFFIX, "page\ttext\n1\tnote".encodeToByteArray())
            scopes.uploadPut(project, id + ProjectNodes.EXTRACT_SUFFIX, "completed extraction".encodeToByteArray())
            completion(scopes, registry, project, id, "completed extraction")
        })
        try {
            withTimeout(5_000) {
                val first = async { corpus.extract(scope.name, "book.pdf", 1_000) }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { corpus.extract(scope.name, "book.pdf", 1_000) }
                assertEquals(1, calls)
                release.complete(Unit)
                assertEquals("completed extraction", first.await()?.text)
                assertEquals("completed extraction", second.await()?.text)
                assertEquals(1, calls, "the waiter must recheck the twins after the first extraction completes")
            }
        } finally { release.complete(Unit); scopes.drain() }
    }

    private fun completion(scopes: ProjectScopes, registry: ProjectDbRegistry, project: String, id: String, text: String) {
        val db = registry.get(project)!!
        scopes.uploadPut(project, id + ProjectNodes.EXTRACTION_SUFFIX, jsonOf(mapOf(
            "originalCid" to db.gateway.getAttachmentRef(id)!!.contentId.value,
            "originalRev" to db.store.head.getRev(id), "extractedTextCid" to ContentId.of(text.encodeToByteArray()).value,
            "anchors" to emptyList<Any>(), "unresolved" to listOf("page", "table", "diagram"))).encodeToByteArray())
    }

    @Test
    fun revisedOriginalInvalidatesItsExtractionEvenWithUnchangedBytes(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("pdf-revision")
        val bytes = "%PDF-1.7\nfixture".encodeToByteArray()
        scopes.uploadPut(scope.name, "book.pdf", bytes)
        scopes.uploadPut(scope.name, "book.pdf.extract.md", "old extraction".encodeToByteArray())
        completion(scopes, registry, scope.name, "book.pdf", "old extraction")
        scopes.uploadPut(scope.name, "book.pdf", bytes)
        var calls = 0
        val corpus = JvmProjectCorpus(registry, scopes, ingest = { project, id ->
            calls++
            scopes.uploadPut(project, id + ProjectNodes.EXTRACT_SUFFIX, "new extraction".encodeToByteArray())
            completion(scopes, registry, project, id, "new extraction")
        })
        try {
            assertEquals("new extraction", corpus.extract(scope.name, "book.pdf", 1000)?.text)
            assertEquals(1, calls)
            assertEquals(listOf("book.pdf"), corpus.docs(scope.name).map { it.id })
        } finally { scopes.drain() }
    }
}
