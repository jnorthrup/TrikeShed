package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import borg.trikeshed.userspace.nio.file.spi.BoundedFileReader
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** Actual main-store worktree reconciliation, with a deliberately observable CAS boundary. */
class PrimaryBoundedCorpusTest {
    @TempDir lateinit var directory: Path

    private class Cas : CasStore(), BoundedCasReader {
        val lengths = mutableMapOf<ContentId, Int>()
        val limits = mutableListOf<Int>()
        var target: ContentId? = null
        var unbounded = 0
        var outcome = "normal"
        var after: (() -> Unit)? = null

        override fun put(bytes: ByteArray): ContentId = super.put(bytes).also { lengths[it] = bytes.size }
        override fun get(cid: ContentId): ByteArray? {
            if (cid == target) { unbounded++; error("primary content must not use unbounded CAS get") }
            return super.get(cid)
        }
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            if (cid == target) limits.add(maxBytes)
            if (outcome == "unsupported") throw UnsupportedOperationException("no bounded backend")
            if (outcome == "missing") return null
            if ((lengths[cid] ?: 0) > maxBytes) throw BoundedReadLimitException(maxBytes)
            val value = super.get(cid) ?: return null
            val callback = after; after = null; callback?.invoke()
            if (outcome == "wrong-bytes") return ByteArray(value.size) { 'x'.code.toByte() }
            return value
        }
    }

    private class CountingFiles(private val delegate: JvmFileOperations = JvmFileOperations()) :
        FileOperations by delegate, BoundedFileReader {
        var tracked = ""
        var contentReads = 0
        override fun readBounded(path: String, maxBytes: Int): ByteArray {
            if (path == tracked) contentReads++
            return delegate.readBounded(path, maxBytes)
        }
    }

    private class Rig(root: File) {
        val cas = Cas()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val files = JvmFileOperations()
        val registry = ProjectDbRegistry("trikeshed")
        val scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))),
            cas, null, projectDbs = registry, ledgerFile = File(root, "ledger.tsv"), filesRoot = File(root, "clones"))
        val repo = File(root, "primary").apply { mkdirs() }
        val prefix = "projects/primary/"

        fun reconcile() {
            val snapshot = WorktreeCouchGateway(files, gateway, prefix = prefix)
                .reconcile(repo.absolutePath, "fixture", "snapshot", 1)
            val previous = WorktreeCouchGateway.WORKTREE_PREFIX
            try { WorktreeCouchGateway.WORKTREE_PREFIX = prefix; scopes.registerPrimary(repo, snapshot.paths.size) }
            finally { WorktreeCouchGateway.WORKTREE_PREFIX = previous }
        }

        fun corpus(reader: BoundedCasReader? = cas, cap: Int = 16) = JvmProjectCorpus(registry, scopes,
            primary = PrimaryProjectBinding("primary", prefix, store, gateway, repo.absolutePath, reader, cap))

        fun rewrite(id: String, bytes: ByteArray, declaredLength: Long, type: String = "text/plain") {
            gateway.putAttachment(OroborosAttachmentRef(prefix + id, type, declaredLength,
                ContentId.of(bytes), "fixture", "rewrite", 2), bytes)
        }

        fun wire(corpus: JvmProjectCorpus): PatchWire {
            val keys = KeyMux {}
            return PatchWire(BrainClient(apiKey = "fixture", keyMux = keys, modelMux = ModelMux(keys) {}),
                scopes, attachments = gateway, corpus = corpus)
        }
    }

    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!
    private fun json(response: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = reify(response.body) as Map<*, *>

    @Test fun primaryReadsUseBoundedVerifiedBytesAndListingIsExplicitlyUnchecked(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        File(rig.repo, "doc.txt").writeText("source")
        rig.reconcile()
        val corpus = rig.corpus()
        try {
            assertFalse(corpus.intakeEligible("primary"), "primary read support must not enable intake")
            val cid = ContentId.of("source".encodeToByteArray())
            rig.cas.target = cid
            val listing = corpus.docs("primary").single()
            assertEquals("unchecked-metadata", listing.toMap()["contentStatus"])
            assertTrue(rig.cas.limits.isEmpty(), "listing may not fetch content")
            val source = assertNotNull(corpus.document("primary", "doc.txt"))
            assertEquals("verified-bytes", source.toMap()["contentStatus"])
            assertEquals("source", corpus.read("primary", "doc.txt")?.text)
            assertContentEquals("source".encodeToByteArray(), corpus.bytes("primary", "doc.txt"))
            assertTrue(rig.cas.limits.isNotEmpty())
            assertTrue(rig.cas.limits.all { it == 16 })
            assertEquals(0, rig.cas.unbounded)

            val response = get(rig.wire(corpus), "/api/projects/primary/docs/doc.txt")
            assertEquals(200, response.status, response.body)
            val payload = json(response)
            val revision = payload["sourceRevision"] as Map<*, *>
            assertEquals(cid.value, revision["cid"])
            assertEquals(source.rev, revision["rev"])
            assertEquals("verified-bytes", revision["contentStatus"])
        } finally { rig.scopes.drain() }
    }

    @Test fun lyingShortMetadataCannotBypassTheCapOrFallBackToCasGet(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val large = "a".repeat(40).encodeToByteArray()
        File(rig.repo, "doc.txt").writeBytes(large)
        rig.reconcile()
        rig.rewrite("doc.txt", large, declaredLength = 3)
        rig.cas.target = ContentId.of(large)
        val corpus = rig.corpus(cap = 16)
        try {
            assertEquals("unchecked-metadata", corpus.docs("primary").single().toMap()["contentStatus"])
            assertNull(corpus.document("primary", "doc.txt"))
            assertNull(corpus.read("primary", "doc.txt"))
            assertNull(corpus.bytes("primary", "doc.txt"))
            assertTrue(rig.cas.limits.isNotEmpty(), "the bounded backend must enforce the cap despite the false declared length")
            assertTrue(rig.cas.limits.all { it == 16 })
            assertEquals(0, rig.cas.unbounded)
        } finally { rig.scopes.drain() }
    }

    @Test fun missingOrUnsupportedReaderNeverUsesTheUnboundedGateway(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val bytes = "source".encodeToByteArray()
        File(rig.repo, "doc.txt").writeBytes(bytes)
        rig.reconcile()
        rig.cas.target = ContentId.of(bytes)
        try {
            val readers: List<BoundedCasReader?> = _l[null, rig.cas]
            for (reader in readers) {
                if (reader != null) rig.cas.outcome = "unsupported"
                val corpus = rig.corpus(reader)
                assertNull(corpus.document("primary", "doc.txt"))
                assertNull(corpus.read("primary", "doc.txt"))
                assertNull(corpus.bytes("primary", "doc.txt"))
                assertEquals("unchecked-metadata", corpus.docs("primary").single().toMap()["contentStatus"])
            }
            rig.cas.outcome = "missing"
            assertNull(rig.corpus().document("primary", "doc.txt"))
            assertEquals(0, rig.cas.unbounded)
        } finally { rig.scopes.drain() }
    }

    @Test fun wrongDeclaredLengthOrDigestAndChangedHeadFailClosed(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val bytes = "source".encodeToByteArray()
        File(rig.repo, "doc.txt").writeBytes(bytes)
        rig.reconcile()
        rig.rewrite("doc.txt", bytes, declaredLength = 2)
        rig.cas.target = ContentId.of(bytes)
        val corpus = rig.corpus()
        try {
            assertNull(corpus.document("primary", "doc.txt"))
            assertNull(corpus.read("primary", "doc.txt"))
            assertNull(corpus.bytes("primary", "doc.txt"))
            rig.rewrite("doc.txt", bytes, declaredLength = bytes.size.toLong())
            rig.cas.outcome = "wrong-bytes"
            assertNull(corpus.document("primary", "doc.txt"), "the consumer rechecks even a misbehaving reader")
            assertNull(corpus.read("primary", "doc.txt"))
            assertNull(corpus.bytes("primary", "doc.txt"))
            rig.cas.outcome = "normal"
            rig.cas.after = { rig.rewrite("doc.txt", "newer".encodeToByteArray(), 5) }
            assertNull(corpus.document("primary", "doc.txt"), "a revision changed during its bounded read")
            assertEquals(0, rig.cas.unbounded)
        } finally { rig.scopes.drain() }
    }

    @Test fun binaryTextReadDoesNotFetchWhileBinaryBytesStillUseTheBoundedReader(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        val bytes = "%PDF-1.7".encodeToByteArray()
        File(rig.repo, "scan.pdf").writeBytes(bytes)
        rig.reconcile()
        rig.cas.target = ContentId.of(bytes)
        val corpus = rig.corpus()
        try {
            assertNull(corpus.read("primary", "scan.pdf"))
            assertTrue(rig.cas.limits.isEmpty(), "binary text refusal must inspect metadata before allocating")
            assertContentEquals(bytes, corpus.bytes("primary", "scan.pdf"))
            assertEquals(_l[16], rig.cas.limits)
            assertEquals(0, rig.cas.unbounded)
        } finally { rig.scopes.drain() }
    }

    @Test fun malformedDeclaredLengthIsNotAValidatedEmptyAttachment(): Unit = runBlocking {
        val rig = Rig(directory.toFile())
        File(rig.repo, "empty.txt").writeBytes(byteArrayOf())
        rig.reconcile()
        val id = rig.prefix + "empty.txt"
        val original = requireNotNull(rig.store.get(id))
        try {
            for (fields in listOf(original.fields.filterNot { it.name == "length" },
                original.fields.map { if (it.name == "length") it.copy(value = "bad") else it })) {
                assertTrue(rig.store.put(original.copy(fields = fields), rig.store.head.getRev(id)))
                assertNull(rig.corpus().document("primary", "empty.txt"))
                assertNull(rig.corpus().bytes("primary", "empty.txt"))
                assertNull(rig.corpus().read("primary", "empty.txt"))
            }
        } finally { rig.scopes.drain() }
    }

    @Test fun packReferenceRejectsActualOversizeBeforeTheBoundedFilesystemRead(): Unit = runBlocking {
        val files = CountingFiles()
        PackCasStore(files, directory.resolve("pack-cas").toString()).use { cas ->
            val store = CouchStoreFactory.casBacked(cas)
            val gateway = CouchAttachmentGateway(store, cas)
            val registry = ProjectDbRegistry("trikeshed")
            val scopes = ProjectScopes(files, gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))),
                cas, null, projectDbs = registry, ledgerFile = directory.resolve("ledger.tsv").toFile(),
                filesRoot = directory.resolve("clones").toFile())
            val repo = directory.resolve("primary").toFile().apply { mkdirs() }
            val source = File(repo, "doc.txt")
            val bytes = "a".repeat(40).encodeToByteArray()
            source.writeBytes(bytes)
            val prefix = "projects/primary/"
            val snapshot = WorktreeCouchGateway(files, gateway, prefix = prefix)
                .reconcile(repo.absolutePath, "fixture", "first", 1)
            val previous = WorktreeCouchGateway.WORKTREE_PREFIX
            try { WorktreeCouchGateway.WORKTREE_PREFIX = prefix; scopes.registerPrimary(repo, snapshot.paths.size) }
            finally { WorktreeCouchGateway.WORKTREE_PREFIX = previous }
            gateway.putAttachment(OroborosAttachmentRef(prefix + "doc.txt", "text/plain", 1,
                ContentId.of(bytes), "fixture", "lying-length", 2), bytes, source.absolutePath)
            files.tracked = source.absolutePath
            val corpus = JvmProjectCorpus(registry, scopes,
                primary = PrimaryProjectBinding("primary", prefix, store, gateway, repo.absolutePath, cas, 16))
            try {
                assertNull(corpus.document("primary", "doc.txt"))
                assertNull(corpus.read("primary", "doc.txt"))
                assertNull(corpus.bytes("primary", "doc.txt"))
                assertEquals(0, files.contentReads, "the real REF size gate must refuse before filesystem allocation")
                assertEquals("unchecked-metadata", corpus.docs("primary").single().toMap()["contentStatus"])
            } finally { scopes.drain() }
        }
    }
}
