package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProjectCorpusVisitTest {
    private class StopVisit : RuntimeException()
    private class Fixture {
        val home = Files.createTempDirectory("project-visit").toFile()
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val corpus = JvmProjectCorpus(registry, scopes)
        suspend fun close() { scopes.drain(); home.deleteRecursively() }
    }

    @Test
    fun visitorCanStopBeforeASecondDocumentIsRead(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "first.pdf", "first".encodeToByteArray())
            val pdb = requireNotNull(f.registry.get(project))
            pdb.store.put(Document("bad.pdf", _l[Field("contentId", "invalid")]))
            var emitted = 0
            assertFailsWith<StopVisit> {
                f.corpus.visit(project) { emitted++; throw StopVisit() }
            }
            assertEquals(1, emitted)
        } finally { f.close() }
    }

    @Test
    fun mountedVisitorMatchesGatewayDeletionAndMissingCidRules(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "keep.pdf", "keep".encodeToByteArray())
            val pdb = requireNotNull(f.registry.get(project))
            pdb.store.put(Document("marked.pdf", _l[Field("deleted", "true"), Field("contentId", "invalid")]))
            pdb.store.put(Document("no-cid.pdf", _l[Field("contentType", "application/pdf")]))
            val visited = mutableListOf<borg.trikeshed.lcnc.ProjectDoc>()
            f.corpus.visit(project) { visited.add(it) }
            val listed = f.corpus.docs(project, limit = Int.MAX_VALUE)
            assertEquals(listed.associateBy { it.id }, visited.associateBy { it.id })
            assertEquals(_s["keep.pdf", "no-cid.pdf"], visited.map { it.id }.toSet())
        } finally { f.close() }
    }

    @Test
    fun visitorRetainsCommittedIdentityWhenFieldListsAreMutated(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            val pdb = requireNotNull(f.registry.get(project))
            val original = ContentId.of("original".encodeToByteArray()).value
            val replacement = ContentId.of("replacement".encodeToByteArray()).value
            val fields = mutableListOf(Field("contentId", original), Field("contentType", "application/pdf"), Field("length", "8"))
            assertTrue(pdb.store.put(Document("mutable.pdf", fields)))
            val revision = requireNotNull(pdb.store.head.getRev("mutable.pdf"))
            val sequence = requireNotNull(pdb.store.head.sequenceOf("mutable.pdf"))
            fields[0] = Field("contentId", replacement)
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(pdb.store.get("mutable.pdf")).fields as? MutableList<Field>)?.let {
                runCatching { it[0] = Field("contentId", replacement) }
            }
            val visited = mutableListOf<borg.trikeshed.lcnc.ProjectDoc>()
            f.corpus.visit(project) { visited.add(it) }
            val row = visited.single()
            assertEquals(original, row.cid)
            assertEquals(revision, row.rev)
            assertEquals(sequence, row.seq)
            val current = assertNotNull(f.corpus.document(project, "mutable.pdf"))
            assertEquals(original, current.cid)
            assertEquals(revision, current.rev)
            assertEquals(sequence, current.seq)
        } finally { f.close() }
    }

    @Test
    fun duplicateAttachmentFieldsAreRejectedBeforeRevisionPublication(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            val pdb = requireNotNull(f.registry.get(project))
            val a = ContentId.of("a".encodeToByteArray()).value
            val b = ContentId.of("b".encodeToByteArray()).value
            assertFailsWith<IllegalArgumentException> {
                pdb.store.put(Document("duplicate.pdf", _l[Field("contentId", a), Field("contentId", b)]))
            }
            assertNull(pdb.store.head.getRev("duplicate.pdf"))
        } finally { f.close() }
    }

    @Test
    fun conflictsAndIdempotentReplicationShortCircuitBeforeBodyCapture(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        assertTrue(store.put(Document("book", _l[Field("contentId", ContentId.of("a".encodeToByteArray()).value)])))
        val current = requireNotNull(store.head.getRev("book"))
        val invalid = Document("book", _l[Field("contentId", "a"), Field("contentId", "b")])
        assertFalse(store.put(invalid, "0-stale"))
        assertTrue(store.putReplicated(null, "book", current, false))
        assertFalse(store.putReplicated(null, "book", "0-stale", false))
        assertEquals(current, store.head.getRev("book"))
    }

    @Test
    fun casStoreOwnsTheBodyBeforeHashingAndPublishingItsRevision(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "hash gate timed out" }
                }
                return super.put(bytes)
            }
        }
        val store = CouchStoreFactory.casBacked(cas)
        val original = ContentId.of("original".encodeToByteArray()).value
        val replacement = ContentId.of("replacement".encodeToByteArray()).value
        val nested = mutableListOf("original")
        val fields = mutableListOf(Field("contentId", original), Field("nested", nested))
        val expected = ContentId.of(CouchStoreFactory.canonicalBody(Document("book.pdf", fields))).value
        val write = async(Dispatchers.Default) { store.put(Document("book.pdf", fields)) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            fields[0] = Field("contentId", replacement)
            nested[0] = "replacement"
            release.countDown()
            assertTrue(withTimeout(5_000) { write.await() })
            val saved = assertNotNull(store.get("book.pdf"))
            assertEquals(original, saved.fields.first { it.name == "contentId" }.value)
            assertEquals(_l["original"], saved.fields.first { it.name == "nested" }.value)
            assertEquals("1-$expected", store.head.getRev("book.pdf"))
        } finally { release.countDown() }
    }

    @Test
    fun casCapturePreservesSupportedCompoundTypesAndOwnsTheirContents(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        val bytes = _a[1.toByte(), 2.toByte()]
        val array = arrayOf("alpha", "beta")
        val nested = mutableListOf<Any>(7, 1.5f)
        val map = mutableMapOf<Any, Any>("nested" to nested, 12 to "integer-key")
        val tagged = Item.Tag(42uL, itemArrayOf(Item.Bin(bytes), Item.Nil, itemMapOf("count" to Item.Num(3))))
        val doc = Document("supported", _l[
            Field("bytes", bytes), Field("array", array), Field("map", map),
            Field("tag", tagged), Field("nil", Item.Nil),
        ])
        val expected = ContentId.of(CouchStoreFactory.canonicalBody(doc)).value
        assertTrue(store.put(doc))
        bytes[0] = 9
        array[0] = "changed"
        nested[0] = 99
        map.remove(12)
        val saved = assertNotNull(store.get("supported")).fields.associate { it.name to it.value }
        assertContentEquals(_a[1.toByte(), 2.toByte()], saved.getValue("bytes") as ByteArray)
        val savedArray = saved.getValue("array") as Array<*>
        assertEquals(String::class.java, savedArray.javaClass.componentType)
        assertEquals(_l["alpha", "beta"], savedArray.toList())
        val savedMap = saved.getValue("map") as Map<*, *>
        assertEquals("integer-key", savedMap[12])
        assertEquals(_l[7, 1.5f], savedMap["nested"])
        val savedTag = saved.getValue("tag") as Item.Tag
        assertEquals(42uL, savedTag.tag)
        val item = savedTag.item as Item.Arr
        assertContentEquals(_a[1.toByte(), 2.toByte()], (item[0] as Item.Bin).value)
        assertSame(Item.Nil, saved.getValue("nil"))
        assertEquals("1-$expected", store.head.getRev("supported"))
    }

    @Test
    fun casCaptureRejectsCyclesAndExcessiveDepthBeforePublication(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        val cycle = mutableListOf<Any>()
        cycle.add(cycle)
        assertFailsWith<IllegalArgumentException> { store.put(Document("cycle", _l[Field("value", cycle)])) }
        assertNull(store.head.getRev("cycle"))
        var deep: Any = "leaf"
        repeat(130) { deep = _l[deep] }
        assertFailsWith<IllegalArgumentException> { store.put(Document("deep", _l[Field("value", deep)])) }
        assertNull(store.head.getRev("deep"))
    }

    @Test
    fun casCaptureUsesCanonicalStringForOpaqueValues(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        val opaque = object { override fun toString() = "opaque-value" }
        val doc = Document("opaque", _l[Field("value", opaque)])
        val expected = ContentId.of(CouchStoreFactory.canonicalBody(doc)).value
        assertTrue(store.put(doc))
        assertEquals("opaque-value", assertNotNull(store.get("opaque")).fields.single().value)
        assertEquals("1-$expected", store.head.getRev("opaque"))
    }

    @Test
    fun visitorObservesCurrentRevisionsAndSkipsDeletedOrNewSlots(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "first.pdf", "first".encodeToByteArray())
            f.scopes.uploadPut(project, "second.pdf", "old".encodeToByteArray(), DecisionProvider.JEV)
            f.scopes.uploadPut(project, "gone.pdf", "gone".encodeToByteArray())
            val seen = mutableListOf<borg.trikeshed.lcnc.ProjectDoc>()
            val providers = mutableMapOf<String, DecisionProvider?>()
            var replacement = ""
            f.corpus.visit(project) { document ->
                seen.add(document)
                providers[document.id] = f.scopes.decisionProvider(project, document.id)
                if (document.id == "first.pdf") {
                    replacement = f.scopes.uploadPut(project, "second.pdf", "new".encodeToByteArray(), DecisionProvider.OPENAI).value
                    f.scopes.uploadRemove(project, "gone.pdf")
                    f.scopes.uploadPut(project, "later.pdf", "later".encodeToByteArray())
                }
            }
            assertEquals(_l["first.pdf", "second.pdf"], seen.map { it.id })
            val second = seen.last()
            val current = assertNotNull(f.corpus.document(project, "second.pdf"))
            assertEquals(replacement, second.cid)
            assertEquals(current.rev, second.rev)
            assertEquals(current.seq, second.seq)
            assertEquals(DecisionProvider.OPENAI, providers["second.pdf"])
        } finally { f.close() }
    }

    @Test
    fun visitorFailsIfTheProjectIsRemountedMidScan(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "first.pdf", "first".encodeToByteArray())
            f.scopes.uploadPut(project, "second.pdf", "second".encodeToByteArray())
            val failure = assertFailsWith<IllegalStateException> {
                f.corpus.visit(project) {
                    f.scopes.unmount(project)
                    f.scopes.beginUpload(project)
                    f.scopes.uploadPut(project, "replacement.pdf", "replacement".encodeToByteArray())
                }
            }
            assertContains(failure.message.orEmpty(), "backing changed")
        } finally { f.close() }
    }

    @Test
    fun missingProjectIsAnIncompleteVisit(): Unit = runBlocking {
        val f = Fixture()
        try {
            assertFailsWith<borg.trikeshed.lcnc.ProjectVisitUnavailable> { f.corpus.visit("missing") { } }
        } finally { f.close() }
    }

    @Test
    fun visitorEmitsOnCallerContextAndStopsWhenItsJobIsCancelled(): Unit = runBlocking {
        val f = Fixture()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "first.pdf", "first".encodeToByteArray())
            f.scopes.uploadPut(project, "second.pdf", "second".encodeToByteArray())
            val entered = CompletableDeferred<Unit>()
            var count = 0
            val visit = launch {
                withContext(dispatcher) {
                    val caller = Thread.currentThread()
                    f.corpus.visit(project) {
                        assertSame(caller, Thread.currentThread())
                        count++
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            withTimeout(5_000) { entered.await(); visit.cancelAndJoin() }
            assertEquals(1, count)
            assertNotNull(f.corpus.document(project, "second.pdf"))
        } finally { dispatcher.close(); f.close() }
    }

    @Test
    fun visitorDoesNotSwallowCancellationCaughtByItsLastCallback(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            f.scopes.uploadPut(project, "only.pdf", "document".encodeToByteArray())
            val entered = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Boolean>()
            val visit = launch {
                try {
                    f.corpus.visit(project) {
                        entered.complete(Unit)
                        try { awaitCancellation() } catch (_: CancellationException) { }
                    }
                    returned.complete(true)
                } catch (_: CancellationException) { returned.complete(false) }
            }
            withTimeout(5_000) { entered.await(); visit.cancelAndJoin() }
            assertFalse(returned.await(), "visit returned normally from a cancelled last callback")
        } finally { f.close() }
    }

    @Test
    fun concurrentVisitsOnlyEmitCommittedRevisionTuples(): Unit = runBlocking {
        val f = Fixture()
        try {
            val project = f.scopes.beginUpload("project").name
            repeat(12) { f.scopes.uploadPut(project, "book-$it.pdf", "initial-$it".encodeToByteArray()) }
            val start = CompletableDeferred<Unit>()
            val seen = mutableListOf<borg.trikeshed.lcnc.ProjectDoc>()
            val writer = async(Dispatchers.Default) {
                start.await()
                repeat(250) { f.scopes.uploadPut(project, "book-${it % 12}.pdf", "revision-$it".encodeToByteArray()) }
            }
            val reader = async(Dispatchers.Default) {
                start.await()
                repeat(25) { f.corpus.visit(project) { seen.add(it) } }
            }
            start.complete(Unit)
            writer.await(); reader.await()
            val frames = requireNotNull(f.registry.get(project)).store.changes.series()
            val committed = HashMap<Triple<String, String, Long>, String>()
            for (i in 0 until frames.size) {
                val frame = frames[i]
                val cid = frame.doc?.fields?.firstOrNull { it.name == "contentId" }?.value as? String ?: continue
                committed[Triple(frame.docId, frame.rev, frame.sequence)] = cid
            }
            assertTrue(seen.isNotEmpty())
            for (document in seen) {
                assertEquals(committed[Triple(document.id, document.rev, document.seq)], document.cid,
                    "visitor emitted a CID with an unrelated committed revision")
            }
        } finally { f.close() }
    }
}
