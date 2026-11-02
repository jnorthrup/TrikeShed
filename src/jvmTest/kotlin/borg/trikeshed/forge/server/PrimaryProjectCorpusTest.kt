package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.PackCasStore
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import borg.trikeshed.daemon.primaryWorktreeRoot
import java.io.File
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.test.*

/** Real main-store worktree reconciliation; the primary is never represented by a made-up project DB. */
class PrimaryProjectCorpusTest {
    @TempDir lateinit var directory: Path

    private class Rig(root: File, val cas: CasStore = FileCasStore(JvmFileOperations(), File(root, "fixture-cas").absolutePath)) {
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val files = JvmFileOperations()
        val registry = ProjectDbRegistry("trikeshed")
        val scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))),
            cas, null, projectDbs = registry, ledgerFile = File(root, "ledger.tsv"), filesRoot = File(root, "clones"))

        fun register(root: File): PrimaryProjectBinding {
            val name = root.name.lowercase()
            val prefix = "projects/$name/"
            val snapshot = WorktreeCouchGateway(files, gateway, prefix = prefix)
                .reconcile(root.absolutePath, "fixture", "first", 1)
            val prior = WorktreeCouchGateway.WORKTREE_PREFIX
            try {
                WorktreeCouchGateway.WORKTREE_PREFIX = prefix
                scopes.registerPrimary(root, snapshot.paths.size)
            } finally { WorktreeCouchGateway.WORKTREE_PREFIX = prior }
            return PrimaryProjectBinding(name, prefix, store, gateway, root.absolutePath, reader = cas as? BoundedCasReader)
        }

        fun corpus(binding: PrimaryProjectBinding) = JvmProjectCorpus(registry, scopes, primary = binding)
        fun wire(corpus: JvmProjectCorpus): PatchWire {
            val keys = KeyMux {}
            return PatchWire(BrainClient(apiKey = "fixture", keyMux = keys, modelMux = ModelMux(keys) {}),
                scopes, attachments = gateway, corpus = corpus)
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!
    private fun json(response: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = reify(response.body) as Map<*, *>

    @Test fun primaryReadsUsePhysicalMainStoreRevisionsButExposeLogicalIds(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val primary = File(directory.toFile(), "Docs and law+&").apply { mkdirs() }
        val id = "notes/terms +1.txt"
        val source = File(primary, id).apply { parentFile.mkdirs(); writeText("first edition") }
        File(primary, "readme.md").writeText("# Primary")
        val pdf = File(primary, "scan.pdf").apply { writeBytes("%PDF-fixture-v1".encodeToByteArray()) }
        File(primary, "scan.pdf.extract.md").writeText("unverified extraction")
        val binding = rig.register(primary)
        val neighbor = File(directory.toFile(), "neighbor").apply { mkdirs() }
        File(neighbor, "secret.txt").writeText("neighbor")
        WorktreeCouchGateway(rig.files, rig.gateway, prefix = binding.prefix.removeSuffix("/") + "-backup/")
            .reconcile(neighbor.absolutePath, "fixture", "first", 1)
        val secondary = File(directory.toFile(), "Secondary").apply { mkdirs() }
        File(secondary, id).apply { parentFile.mkdirs(); writeText("secondary edition") }
        val mounted = rig.scopes.mount(secondary.absolutePath)
        val corpus = rig.corpus(binding)
        try {
            assertNull(rig.registry.get(binding.name), "the primary remains in the main store")
            assertEquals(_s[binding.name, mounted.name], corpus.projects().map { it.name }.toSet())
            assertEquals(_s[id, "readme.md", "scan.pdf"], corpus.docs(binding.name).map { it.id }.toSet())
            assertEquals(_l[id], corpus.docs(binding.name, prefix = "notes/", glob = "*.txt").map { it.id })
            assertEquals(emptyList(), corpus.docs(binding.name, prefix = "secret"), "prefix-neighbor data is not in the project")
            assertEquals(emptyList(), corpus.docs("unknown"))
            val first = assertNotNull(corpus.document(binding.name, id))
            val text = assertNotNull(corpus.read(binding.name, id))
            assertEquals(binding.name, first.project)
            assertEquals(id, first.id)
            assertEquals("first edition", text.text)
            assertEquals(id, text.id)
            assertEquals(ContentId.of("first edition".encodeToByteArray()).value, first.cid)
            assertEquals(rig.store.head.getRev(binding.prefix + id), first.rev)
            assertEquals(rig.store.head.sequenceOf(binding.prefix + id), first.seq)
            assertEquals(first.rev, text.rev)
            assertEquals(first.seq, text.seq)
            assertContentEquals("first edition".encodeToByteArray(), corpus.bytes(binding.name, id))
            assertEquals("secondary edition", corpus.read(mounted.name, id)?.text)
            assertNull(corpus.document("unknown", id))
            assertNull(corpus.bytes("unknown", id))
            assertNull(corpus.read("unknown", id))
            assertNull(corpus.extract(binding.name, id, 100), "read-only primary access must not imply source admission")
            assertNull(corpus.extract(binding.name, "scan.pdf", 100), "a stored twin must not imply a current extraction")
            assertContentEquals("%PDF-fixture-v1".encodeToByteArray(), corpus.bytes(binding.name, "scan.pdf"),
                "binary original bytes remain available through the read-only corpus")
            val admission = assertFailsWith<IllegalArgumentException> { rig.scopes.admitDocument(binding.name, id) }
            assertContains(admission.message.orEmpty(), "no project db", "curation admission is a separate, still-unavailable capability")

            val wire = rig.wire(corpus)
            val base = "/api/projects/" + enc(binding.name) + "/docs"
            val listed = get(wire, base)
            assertEquals(200, listed.status, listed.body)
            assertEquals(binding.name, json(listed)["project"])
            val response = get(wire, base + "/" + enc(id))
            assertEquals(200, response.status, response.body)
            val doc = json(response)
            assertEquals(id, doc["id"])
            assertEquals("first edition", doc["text"])
            assertEquals(first.cid, (doc["sourceRevision"] as Map<*, *>)["cid"])
            assertEquals(first.rev, (doc["sourceRevision"] as Map<*, *>)["rev"])
            assertEquals(first.cid, (doc["displayTextRevision"] as Map<*, *>)["cid"])

            source.writeText("second edition")
            pdf.writeBytes("%PDF-fixture-v2".encodeToByteArray())
            WorktreeCouchGateway(rig.files, rig.gateway, prefix = binding.prefix)
                .reconcile(primary.absolutePath, "fixture", "second", 2)
            val revised = assertNotNull(corpus.document(binding.name, id))
            assertNotEquals(first.cid, revised.cid)
            assertNotEquals(first.rev, revised.rev)
            assertNotEquals(first.seq, revised.seq)
            assertEquals("second edition", corpus.read(binding.name, id)?.text)
            assertEquals("secondary edition", corpus.read(mounted.name, id)?.text, "mounted DB is separate")
            val pdfResponse = json(get(wire, base + "/scan.pdf"))
            val pdfSource = pdfResponse["sourceRevision"] as Map<*, *>
            val pdfText = pdfResponse["displayTextRevision"] as Map<*, *>
            assertEquals(ContentId.of("%PDF-fixture-v2".encodeToByteArray()).value, pdfSource["cid"])
            assertEquals(ContentId.of("unverified extraction".encodeToByteArray()).value, pdfText["cid"])
            assertNotEquals(pdfSource["cid"], pdfText["cid"])
            assertNull(corpus.read(binding.name, "scan.pdf.extract.md")?.originalCid,
                "a twin without a lineage record must not claim a source")
            assertNull(corpus.extract(binding.name, "scan.pdf", 100))

            assertTrue(source.delete())
            WorktreeCouchGateway(rig.files, rig.gateway, prefix = binding.prefix)
                .reconcile(primary.absolutePath, "fixture", "third", 3)
            assertNull(corpus.document(binding.name, id))
            assertNull(corpus.read(binding.name, id))
            assertNull(corpus.bytes(binding.name, id))
            assertFalse(corpus.docs(binding.name).any { it.id == id })
            assertEquals(404, get(wire, base + "/" + enc(id)).status)
        } finally { rig.scopes.drain() }
    }

    @Test fun duplicatePrimaryAndMountedAliasIsRefusedRatherThanSelected(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val secondary = File(directory.toFile(), "mounted/clash").apply { mkdirs() }
        File(secondary, "doc.txt").writeText("secondary")
        rig.scopes.mount(secondary.absolutePath)
        val primary = File(directory.toFile(), "primary/clash").apply { mkdirs() }
        File(primary, "doc.txt").writeText("primary")
        val binding = rig.register(primary)
        val corpus = rig.corpus(binding)
        try {
            assertFailsWith<IllegalStateException> { corpus.projects() }
            assertFailsWith<IllegalStateException> { corpus.docs(binding.name) }
            assertFailsWith<IllegalStateException> { corpus.document(binding.name, "doc.txt") }
            assertFailsWith<IllegalStateException> { corpus.read(binding.name, "doc.txt") }
            assertFailsWith<IllegalStateException> { corpus.bytes(binding.name, "doc.txt") }
            assertFailsWith<IllegalStateException> { corpus.extract(binding.name, "doc.txt", 100) }
        } finally { rig.scopes.drain() }
    }

    @Test fun mismatchedPrimaryPrefixIsRefused(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val primary = File(directory.toFile(), "primary").apply { mkdirs() }
        File(primary, "doc.txt").writeText("primary")
        val binding = rig.register(primary)
        val previous = WorktreeCouchGateway.WORKTREE_PREFIX
        try {
            WorktreeCouchGateway.WORKTREE_PREFIX = "projects/another/"
            rig.scopes.registerPrimary(primary, 1)
        } finally { WorktreeCouchGateway.WORKTREE_PREFIX = previous }
        val wrong = rig.corpus(binding)
        try {
            assertFailsWith<IllegalStateException> { wrong.projects() }
            assertFailsWith<IllegalStateException> { wrong.docs(binding.name) }
            assertFailsWith<IllegalStateException> { wrong.read(binding.name, "doc.txt") }
        } finally { rig.scopes.drain() }
    }

    @Test fun primaryBindingCannotPairAnUnrelatedStoreAndGateway() {
        val rig = Rig(directory.toFile())
        val foreign = CouchStoreFactory.casBacked(CasStore.inMemory())
        assertFailsWith<IllegalArgumentException> {
            PrimaryProjectBinding("primary", "projects/primary/", foreign, rig.gateway, directory.toFile().absolutePath)
        }
    }

    @Test fun primaryRootNormalizesRelativeDotAndTrailingSlashWithoutLosingNamedAliases() {
        val named = File(directory.toFile(), "named worktree").apply { mkdirs() }
        assertEquals(named.absolutePath, primaryWorktreeRoot(File(named.path + "/")).absolutePath)
        assertEquals(File("relative-worktree").absolutePath, primaryWorktreeRoot(File("relative-worktree")).absolutePath)
        assertEquals(File(".").canonicalPath, primaryWorktreeRoot(File(".")).absolutePath)
        assertEquals(File("..").canonicalPath, primaryWorktreeRoot(File("..")).absolutePath)
    }

    @Test fun aDifferentDirectoryWithTheSameNameCannotReplaceTheBoundPrimary(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val original = File(directory.toFile(), "first/worktree").apply { mkdirs() }
        File(original, "doc.txt").writeText("original")
        val binding = rig.register(original)
        val replacement = File(directory.toFile(), "second/worktree").apply { mkdirs() }
        File(replacement, "doc.txt").writeText("replacement")
        val previous = WorktreeCouchGateway.WORKTREE_PREFIX
        try {
            WorktreeCouchGateway.WORKTREE_PREFIX = binding.prefix
            rig.scopes.registerPrimary(replacement, 1)
        } finally { WorktreeCouchGateway.WORKTREE_PREFIX = previous }
        val corpus = rig.corpus(binding)
        try {
            assertFailsWith<IllegalStateException> { corpus.projects() }
            assertFailsWith<IllegalStateException> { corpus.docs(binding.name) }
            assertFailsWith<IllegalStateException> { corpus.read(binding.name, "doc.txt") }
        } finally { rig.scopes.drain() }
    }

    @Test fun changedReferenceBytesWithTheSameSizeAndTimestampAreNotReturned(): Unit = runBlocking {
        val files = JvmFileOperations()
        PackCasStore(files, directory.resolve("pack-cas").toString()).use { cas ->
            val rig = Rig(directory.toFile(), cas)
            val primary = File(directory.toFile(), "primary").apply { mkdirs() }
            val source = File(primary, "doc.txt").apply { writeText("alpha") }
            val binding = rig.register(primary)
            val corpus = rig.corpus(binding)
            try {
                val original = assertNotNull(corpus.document(binding.name, "doc.txt"))
                val modifiedAt = source.lastModified()
                source.writeText("omega") // Same length, and preserve the stamp a REF normally checks.
                assertTrue(source.setLastModified(modifiedAt))
                assertEquals(modifiedAt, source.lastModified())
                assertContentEquals("omega".encodeToByteArray(), cas.get(ContentId(original.cid)),
                    "this fixture must exercise the unverified reference-read boundary")
                assertNull(corpus.document(binding.name, "doc.txt"), "a stale reference is not a current document")
                assertEquals(_l["doc.txt"], corpus.docs(binding.name).map { it.id })
                assertNull(corpus.read(binding.name, "doc.txt"), "text bytes no longer match their claimed CID")
                assertNull(corpus.bytes(binding.name, "doc.txt"), "original bytes no longer match their claimed CID")
                val wire = rig.wire(corpus)
                assertEquals(503, get(wire, "/api/projects/primary/docs/doc.txt").status)
            } finally { rig.scopes.drain() }
        }
    }
}
