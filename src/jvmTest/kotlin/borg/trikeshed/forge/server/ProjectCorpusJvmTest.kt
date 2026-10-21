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

    private fun rig(): Triple<ProjectScopes, ProjectDbRegistry, ProjectMiner> {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val bridge = CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couchStore)))
        val registry = ProjectDbRegistry("trikeshed")
        val home = File(System.getProperty("java.io.tmpdir"), "corpus-home-${System.nanoTime()}").apply { mkdirs() }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, bridge, cas, null, projectDbs = registry, ledgerFile = File(home, "mount-ledger.tsv"), filesRoot = File(home, "files"))
        return Triple(scopes, registry, ProjectMiner(registry, scopes, cas, null, File(home, "files")))
    }

    private fun origin(scopes: ProjectScopes, registry: ProjectDbRegistry, project: String, id: String) {
        val gateway = registry.get(project)!!.gateway
        val source = gateway.getAttachmentRef(id)!!.contentId.value
        val text = gateway.getAttachmentRef(id + ProjectNodes.EXTRACT_SUFFIX)!!.contentId.value
        scopes.uploadPut(project, id + ".extract-origin.tsv", "$source\t$text".encodeToByteArray())
    }

    private fun html(marker: String) = ("<html><body><h1>Source version</h1><p>Outcome $marker is synthetic regression content. " +
        "Indicator: the extracted text must follow the current uploaded document rather than an older cache entry.</p></body></html>").encodeToByteArray()

    /** Minimal, real one-page PDF; all text is an explicit synthetic fixture. */
    private fun pdf(marker: String): ByteArray {
        val stream = "BT /F1 12 Tf 72 720 Td (Synthetic source-version regression document.) Tj 0 -20 Td " +
            "(Outcome $marker is the version marker in this body text.) Tj 0 -20 Td " +
            "(Indicator: extraction must follow the current uploaded bytes.) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${stream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${stream}endstream")
        val out = java.io.ByteArrayOutputStream()
        fun emit(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        emit("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> out.size().also { emit("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.size()
        emit("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { emit("%010d 00000 n \n".format(it)) }
        emit("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun extractionOriginIsReadableAsText(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("origin-text")
        try {
            val marker = "${ContentId.of("source".encodeToByteArray()).value}\t${ContentId.of("text".encodeToByteArray()).value}"
            scopes.uploadPut(scope.name, "document.html.extract-origin.tsv", marker.encodeToByteArray())
            assertEquals(marker, JvmProjectCorpus(registry, scopes).read(scope.name, "document.html.extract-origin.tsv")?.text)
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun ocrCacheUsesConfiguredTemporaryRoot() {
        assertTrue(ProjectMiner.ocrDir("temporary-root", "source.pdf").toPath()
            .startsWith(File(System.getProperty("java.io.tmpdir")).toPath()))
    }

    @Test
    fun batchMiningRefreshesChangedSources(): Unit = runBlocking {
        val (scopes, registry, miner) = rig()
        val scope = scopes.beginUpload("batch-source-version")
        try {
            scopes.uploadPut(scope.name, "version.html", html("ALPHA001"))
            assertEquals(1, miner.mine(scope.name).extracted)
            scopes.uploadPut(scope.name, "version.html", html("BETA0002"))
            assertEquals(1, miner.mine(scope.name).extracted)
            val text = JvmProjectCorpus(registry, scopes, miner).extract(scope.name, "version.html", 10_000)!!.text
            assertTrue(text.contains("BETA0002"), text)
            assertEquals(0, miner.mine(scope.name).total, "an unchanged completed source is not mined again")
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun changedHtmlSourceRefreshesNativeExtraction(): Unit = runBlocking {
        val (scopes, registry, miner) = rig()
        val scope = scopes.beginUpload("changed-html")
        val corpus = JvmProjectCorpus(registry, scopes, miner)
        try {
            scopes.uploadPut(scope.name, "version.html", html("ALPHA001"))
            assertTrue(corpus.extract(scope.name, "version.html", 10_000)!!.text.contains("ALPHA001"))
            scopes.uploadPut(scope.name, "version.html", html("BETA0002"))
            assertEquals(false, miner.recognize(scope.name, "version.html")["ingested"])
            val changed = corpus.extract(scope.name, "version.html", 10_000)!!.text
            assertTrue(changed.contains("BETA0002"), changed)
            assertTrue(!changed.contains("ALPHA001"), changed)
            assertEquals(true, miner.recognize(scope.name, "version.html")["ingested"])
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun unchangedSourceUploadKeepsItsValidExtraction(): Unit = runBlocking {
        val (scopes, registry, miner) = rig()
        val scope = scopes.beginUpload("unchanged-html")
        val corpus = JvmProjectCorpus(registry, scopes, miner)
        try {
            scopes.uploadPut(scope.name, "version.html", html("ALPHA001"))
            val first = corpus.extract(scope.name, "version.html", 10_000)!!
            scopes.uploadPut(scope.name, "version.html", html("ALPHA001"))
            val second = corpus.extract(scope.name, "version.html", 10_000)!!
            assertEquals(first.cid, second.cid)
            assertEquals(first.rev, second.rev)
            assertEquals(first.seq, second.seq)
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun legacyExtractionWithoutOriginIsRebuilt(): Unit = runBlocking {
        val (scopes, registry, miner) = rig()
        val scope = scopes.beginUpload("legacy-extraction")
        try {
            scopes.uploadPut(scope.name, "version.html", html("CURRENT1"))
            scopes.uploadPut(scope.name, "version.html.extract.md", "LEGACY WRONG CONTENT".encodeToByteArray())
            val text = JvmProjectCorpus(registry, scopes, miner).extract(scope.name, "version.html", 10_000)!!.text
            assertTrue(text.contains("CURRENT1"), text)
            assertTrue(!text.contains("LEGACY"), text)
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun pdfPageCacheDoesNotCrossSourceVersions(): Unit = runBlocking {
        val (scopes, registry, miner) = rig()
        val scope = scopes.beginUpload("changed-pdf")
        val corpus = JvmProjectCorpus(registry, scopes, miner)
        try {
            scopes.uploadPut(scope.name, "version.pdf", pdf("ALPHA001"))
            assertEquals("ingested", miner.ingest(scope.name, "version.pdf")["verdict"])
            assertTrue(corpus.extract(scope.name, "version.pdf", 10_000)!!.text.contains("ALPHA001"))
            scopes.uploadPut(scope.name, "version.pdf", pdf("BETA0002"))
            assertEquals("ingested", miner.ingest(scope.name, "version.pdf")["verdict"])
            val changed = corpus.extract(scope.name, "version.pdf", 10_000)!!.text
            assertTrue(changed.contains("BETA0002"), changed)
            assertTrue(!changed.contains("ALPHA001"), changed)
        } finally { ProjectMiner.ocrDir(scope.name, "version.pdf").deleteRecursively(); scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun sourceChangeDuringIngestCannotPublishAnOldAssociation(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("source-race")
        val original = html("ALPHA001")
        scopes.uploadPut(scope.name, "version.html", original)
        val corpus = JvmProjectCorpus(registry, scopes, ingest = { project, id ->
            scopes.uploadPut(project, id, html("BETA0002"))
            val oldText = "ALPHA001 from the earlier source".encodeToByteArray()
            scopes.uploadPut(project, id + ProjectNodes.EXTRACT_SUFFIX, oldText)
            scopes.uploadPut(project, id + ".extract-origin.tsv",
                "${ContentId.of(original).value}\t${ContentId.of(oldText).value}".encodeToByteArray())
        })
        try { assertNull(corpus.extract(scope.name, "version.html", 10_000)) }
        finally { scopes.unmount(scope.name); scopes.drain() }
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
    fun deletedDocumentsDoNotConsumeInventoryLimit(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("deleted-inventory")
        try {
            scopes.uploadPut(scope.name, "a.md", "removed".encodeToByteArray())
            scopes.uploadPut(scope.name, "b.md", "retained".encodeToByteArray())
            scopes.uploadRemove(scope.name, "a.md")
            val corpus = JvmProjectCorpus(registry, scopes)
            assertEquals(listOf("b.md"), corpus.docs(scope.name, limit = 1).map { it.id })
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun deletedTextIsAbsent(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("deleted-read")
        try {
            scopes.uploadPut(scope.name, "a.md", "removed".encodeToByteArray())
            scopes.uploadRemove(scope.name, "a.md")
            assertNull(JvmProjectCorpus(registry, scopes).read(scope.name, "a.md"))
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun allDerivedTwinsAreExcludedFromDocumentInventory(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("derived-inventory")
        try {
            scopes.uploadPut(scope.name, "a.md", "source".encodeToByteArray())
            for (suffix in listOf(ProjectNodes.EXTRACT_SUFFIX, ProjectNodes.NOTES_SUFFIX,
                ProjectNodes.FOOTNOTES_SUFFIX, ProjectNodes.LINKS_SUFFIX, ".extract-origin.tsv")) {
                scopes.uploadPut(scope.name, "a.md" + suffix, "derived".encodeToByteArray())
            }
            assertEquals(listOf("a.md"), JvmProjectCorpus(registry, scopes).docs(scope.name).map { it.id })
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun emptyLiveDocumentIsNotADeletion(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("empty-live-document")
        try {
            scopes.uploadPut(scope.name, "empty.md", byteArrayOf())
            val corpus = JvmProjectCorpus(registry, scopes)
            assertEquals(listOf("empty.md"), corpus.docs(scope.name).map { it.id })
            assertEquals("", corpus.read(scope.name, "empty.md")?.text)
        } finally { scopes.unmount(scope.name); scopes.drain() }
    }

    @Test
    fun pdfExtractReusesItsFootnotesMarkerRegardlessOfMimeType(): Unit = runBlocking {
        val (scopes, registry) = rig()
        val scope = scopes.beginUpload("pdf-marker")
        scopes.uploadPut(scope.name, "book.pdf", "%PDF-fixture".encodeToByteArray())
        scopes.uploadPut(scope.name, "book.pdf.extract.md", "retained extraction".encodeToByteArray())
        scopes.uploadPut(scope.name, "book.pdf.footnotes.tsv", "page\ttext\n1\tnote".encodeToByteArray())
        origin(scopes, registry, scope.name, "book.pdf")
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
            origin(scopes, registry, project, id)
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
}
