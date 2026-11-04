package borg.trikeshed.forge.server

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DocumentEnglishRetainedWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val sourceText = "HEADER\nTenant may remain.\nTAIL"
    private val begin = sourceText.indexOf("Tenant")
    private val end = sourceText.indexOf("\nTAIL")

    /** Synthetic syntax exercises the real receipt boundary; it makes no parser-execution claim. */
    private fun parsed(text: String, copies: Boolean): NlpDocument {
        val tokens = Regex("\\S+").findAll(text).mapIndexed { index, word ->
            NlpToken(index + 1, word.range.first, word.range.last + 1, word.value, word.value.lowercase(), "NN", "O")
        }.toList().toSeries()
        val edges = tokens.view.map { token -> NlpDependency(if (token.index == 1) 0 else 1,
            token.index, if (token.index == 1) "root" else "dep") }.toMutableList()
        if (copies) {
            edges.add(NlpDependency(1, 1, "conj", 0, 1))
            edges.add(NlpDependency(1, 2, "dep", 1, 0))
        }
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges.toSeries())],
            NlpMetadata("synthetic-fixture-no-parser", "DocumentEnglishRetainedWireTest",
                runtime = _m["dependencyNodeIdentity" j "token-index+copy-count/1", "fixtureAuthority" j "synthetic-bindings-only"]))
    }
    private class Hooks {
        val lookups = AtomicInteger()
        val factoryCalls = AtomicInteger()
        val syntheticReads = AtomicInteger()
        var lookup: (suspend (Int) -> Unit)? = null
    }
    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val owner: CompletableJob, val wire: DocumentEnglishWire, val hooks: Hooks,
        val selected: ProjectDoc, val cid: ContentId, val bytes: ByteArray, val receipt: Map<String, Any?>, val casRoot: Path)

    private suspend fun dispatch(ctx: ModuleContext, path: String, body: Map<String, Any?>? = null,
        method: String = "POST", status: Int = 200): WireHttpResponse {
        val claim = assertNotNull(ctx.routes.match(path.substringBefore('?')))
        val raw = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" + (body?.let(::jsonOf) ?: "")
        val response = assertNotNull(claim.route(method, path, raw, null))
        assertEquals(status, response.status, response.body)
        return response
    }
    private fun selection(row: ProjectDoc) = _m["project" j row.project, "id" j row.id, "cid" j row.cid,
        "rev" j row.rev, "seq" j row.seq, "begin" j begin, "end" j end]
    private suspend fun fixture(name: String, parent: Job?, copies: Boolean = true,
        readerFactory: (() -> NlpReader)? = null): Fixture {
        val root = directory.resolve(name); Files.createDirectories(root)
        val files = JvmFileOperations(); val casRoot = root.resolve("cas"); val cas = FileCasStore(files, casRoot.toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas); val corpus = InMemoryProjectCorpus(); val hooks = Hooks()
        val live = object : ProjectCorpus by corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                hooks.lookup?.invoke(hooks.lookups.incrementAndGet())
                return corpus.document(project, id)
            }
        }
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, root.toFile())
        cas.put(sourceText.encodeToByteArray()); corpus.put("legal", "agreement.txt", sourceText.encodeToByteArray(), "text/plain")
        val selected = assertNotNull(corpus.document("legal", "agreement.txt"))
        val producer = DocumentEnglishWire(ctx, live) { NlpReader { hooks.syntheticReads.incrementAndGet(); parsed(it, copies) } }
        producer.attach()
        val produced = try { reifyMap(dispatch(ctx, DocumentEnglishWire.PATH, selection(selected)).body) }
            finally { producer.drain() }
        assertNull(ctx.routes.match(DocumentEnglishWire.PATH)); assertNull(ctx.routes.match(DocumentEnglishWire.RETAINED_PATH))
        val cid = ContentId(produced["receiptCid"] as String)
        val bytes = assertNotNull(cas.getBounded(cid, DocumentEnglishWire.MAX_RECEIPT))
        val wire = DocumentEnglishWire(ctx, live) {
            hooks.factoryCalls.incrementAndGet()
            readerFactory?.invoke() ?: error("Retained opening must not initialize a reader")
        }
        wire.attach(); hooks.lookups.set(0)
        return Fixture(ctx, cas, corpus, owner, wire, hooks, selected, cid, bytes, CanonicalCbor.decodeMap(bytes), casRoot)
    }
    private fun input(f: Fixture, cid: ContentId = f.cid) = selection(f.selected) + _m["receiptCid" j cid.value]
    private suspend fun open(f: Fixture, body: Map<String, Any?> = input(f), status: Int = 200): Map<String, Any?> =
        reifyMap(dispatch(f.ctx, DocumentEnglishWire.RETAINED_PATH, body, status = status).body)
    private suspend fun refused(f: Fixture, body: Map<String, Any?> = input(f)) {
        val value = open(f, body, 409)
        assertFalse(value.containsKey("receiptCid")); assertFalse(value.containsKey("sentences")); assertTrue(value["error"] is String)
    }
    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        try { f.wire.drain() } finally { f.owner.complete(); f.owner.join() }
    }
    private fun retainedFiles(f: Fixture) = f.casRoot.toFile().walkTopDown().filter { it.isFile }.map { it.relativeTo(f.casRoot.toFile()).path }.toSet()

    @Test fun retainedOpeningReturnsTheActualPayloadAndExactOriginalCborWithoutAReaderOrNewReceipt(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("retained-exact", coroutineContext[Job])
            try {
                // A valid explicit null must survive even though ProjectDoc.toMap omits null metadata.
                val nullable = f.receipt + _m["sourceSnapshots" j (f.receipt["sourceSnapshots"] as List<*>).map {
                    (it as Map<*, *>) + _m["contentStatus" j null]
                }]
                val nullableCid = f.cas.put(CanonicalCbor.encodeMap(nullable))
                for (cid in s_[f.cid, nullableCid].view) {
                    val original = assertNotNull(f.cas.getBounded(cid, DocumentEnglishWire.MAX_RECEIPT))
                    val snapshot = EnglishObservationGraph.read(f.cas, cid)
                    val payload = EnglishObservationGraph.retained(snapshot)
                    assertContentEquals(original, CanonicalCbor.encodeMap(payload)); assertEquals(cid, ContentId.of(CanonicalCbor.encodeMap(payload)))
                    val files = retainedFiles(f); val board = f.ctx.blackboard.snapshot()
                    val response = open(f, input(f, cid))
                    assertEquals(cid.value, response["receiptCid"])
                    assertEquals(reifyMap(jsonOf(payload)), response - _s["receiptCid", "retrieval"])
                    val retrieval = response["retrieval"] as Map<*, *>
                    assertEquals("retained-current-inspection", retrieval["kind"])
                    assertEquals(false, retrieval["parserExecuted"]); assertEquals(false, retrieval["modelCalled"])
                    assertEquals("checked-before-and-after-open", retrieval["currentness"])
                    assertEquals(f.selected.cid, response["originalCid"]); assertEquals(f.selected.cid, response["extractedTextCid"])
                    val range = response["range"] as Map<*, *>; assertEquals(begin, (range["begin"] as Number).toInt())
                    assertEquals(end, (range["end"] as Number).toInt()); assertEquals(false, range["complete"])
                    val sentence = (response["sentences"] as List<*>).single() as Map<*, *>
                    assertEquals(sourceText.substring(begin, end), sentence["quote"])
                    assertTrue((sentence["nodes"] as List<*>).any { ((it as Map<*, *>)["copy"] as Number).toInt() == 1 })
                    if (cid == nullableCid) assertTrue((response["sourceSnapshots"] as List<*>).all { (it as Map<*, *>).containsKey("contentStatus") && it["contentStatus"] == null })
                    assertEquals(files, retainedFiles(f)); assertEquals(board, f.ctx.blackboard.snapshot())
                }
                assertEquals(1, f.hooks.syntheticReads.get()); assertEquals(0, f.hooks.factoryCalls.get()); assertNull(f.ctx.beliefBag)
            } finally { close(f) }
        }
    }

    @Test fun validLegacyPayloadKeepsItsOriginalIdentitySpellingWithoutInventingNodes(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("retained-legacy", coroutineContext[Job], copies = false)
            try {
                val legacy = f.receipt + _m[
                    "counts" j ((f.receipt["counts"] as Map<*, *>) - "nodes"),
                    "coverage" j ((f.receipt["coverage"] as Map<*, *>) - _s["dependencyNodeIdentity", "parserDependencyNodeIdentity", "parserNodeIdentityReported"]),
                    "sentences" j (f.receipt["sentences"] as List<*>).map { raw ->
                        val row = raw as Map<*, *>
                        (row - "nodes") + _m["dependencies" j (row["dependencies"] as List<*>).map {
                            (it as Map<*, *>) - _s["governorCopy", "dependentCopy", "governorNode", "dependentNode"]
                        }]
                    }]
                val bytes = CanonicalCbor.encodeMap(legacy); val cid = f.cas.put(bytes)
                val snapshot = EnglishObservationGraph.read(f.cas, cid); assertTrue(snapshot.legacyIdentity)
                assertContentEquals(bytes, CanonicalCbor.encodeMap(EnglishObservationGraph.retained(snapshot)))
                val response = open(f, input(f, cid)); assertEquals(cid.value, response["receiptCid"])
                assertEquals(reifyMap(jsonOf(legacy)), response - _s["receiptCid", "retrieval"])
                assertFalse((response["counts"] as Map<*, *>).containsKey("nodes"))
                for (raw in response["sentences"] as List<*>) {
                    val row = raw as Map<*, *>; assertFalse(row.containsKey("nodes"))
                    for (edge in row["dependencies"] as List<*>) assertTrue(((edge as Map<*, *>).keys intersect
                        _s["governorCopy", "dependentCopy", "governorNode", "dependentNode"]).isEmpty())
                }
                assertEquals(0, f.hooks.factoryCalls.get())
            } finally { close(f) }
        }
    }

    @Test fun retainedOpeningRequiresExactEightFieldsSourceIdentityAndRangeEvenForIdenticalOtherDocuments(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("retained-identity", coroutineContext[Job])
            try {
                val good = input(f)
                for (bad in s_[good - "receiptCid", good + _m["approved" j true], good + _m["begin" j (begin + 1)],
                    good + _m["end" j (end - 1)], good + _m["rev" j "stale"], good + _m["seq" j (f.selected.seq + 1)],
                    good + _m["cid" j ContentId.of("other bytes".encodeToByteArray()).value], good + _m["receiptCid" j "sha256:short"]].view) refused(f, bad)
                f.corpus.put("legal", "copy.txt", sourceText.encodeToByteArray(), "text/plain")
                val other = assertNotNull(f.corpus.document("legal", "copy.txt")); assertEquals(f.selected.cid, other.cid)
                refused(f, selection(other) + _m["receiptCid" j f.cid.value])
                val denied = dispatch(f.ctx, DocumentEnglishWire.RETAINED_PATH, method = "GET", status = 405)
                assertEquals("method_not_allowed", reifyMap(denied.body)["error"])
                assertEquals(f.cid.value, open(f)["receiptCid"]); assertEquals(0, f.hooks.factoryCalls.get())
            } finally { close(f) }
        }
    }

    @Test fun staleSourcesAndALateMutationBeforePublicationReturnNoInspection(): Unit = runBlocking {
        withTimeout(20_000) {
            for (late in s_[false, true].view) {
                val f = fixture(if (late) "retained-late" else "retained-stale", coroutineContext[Job])
                try {
                    fun change() { f.corpus.put("legal", "agreement.txt", "Changed retained source.".encodeToByteArray(), "text/plain") }
                    if (late) f.hooks.lookup = { if (it == 3) change() } else change()
                    refused(f)
                    if (late) assertEquals(3, f.hooks.lookups.get())
                    assertEquals(0, f.hooks.factoryCalls.get())
                } finally { close(f) }
            }
        }
    }

    @Test fun missingMalformedTamperedOversizedAndCorruptRetainedBlocksRefuseBoundedlyWithoutInitializingAReader(): Unit = runBlocking {
        withTimeout(30_000) {
            val f = fixture("retained-invalid", coroutineContext[Job])
            try {
                val malformed = f.cas.put("not canonical CBOR".encodeToByteArray())
                val tampered = f.cas.put(CanonicalCbor.encodeMap(f.receipt + _m["originalCid" j ContentId.of("forged".encodeToByteArray()).value]))
                val unknown = f.cas.put(CanonicalCbor.encodeMap(f.receipt + _m["approved" j true]))
                val oversized = f.cas.put(ByteArray(DocumentEnglishWire.MAX_RECEIPT + 1))
                for (cid in s_[ContentId.of("not retained".encodeToByteArray()), malformed, tampered, unknown, oversized].view) refused(f, input(f, cid))
                assertEquals(f.cid.value, open(f)["receiptCid"])
                val path = f.casRoot.resolve(CasPaths.blob(f.cid))
                Files.write(path, "corrupt private fixture bytes".encodeToByteArray()); refused(f)
                assertEquals(f.cid, f.cas.put(f.bytes)); assertEquals(f.cid.value, open(f)["receiptCid"])
                assertEquals(0, f.hooks.factoryCalls.get()); assertEquals(1, f.hooks.syntheticReads.get())
            } finally { close(f) }
        }
    }

    @Test fun drainWithdrawsBothRoutesAndWaitsForAcceptedInspectionAndRetainedOpening(): Unit = runBlocking {
        withTimeout(20_000) {
            val retainedEntered = CompletableDeferred<Unit>(); val retainedRelease = CompletableDeferred<Unit>()
            val freshEntered = CompletableDeferred<Unit>(); val freshRelease = CompletableDeferred<Unit>()
            val f = fixture("retained-drain", coroutineContext[Job], readerFactory = {
                NlpReader { freshEntered.complete(Unit); freshRelease.await(); parsed(it, copies = true) }
            })
            val once = AtomicBoolean()
            try {
                f.hooks.lookup = { if (once.compareAndSet(false, true)) { retainedEntered.complete(Unit); retainedRelease.await() } }
                val retained = async(start = CoroutineStart.UNDISPATCHED) { open(f) }; retainedEntered.await()
                val fresh = async(start = CoroutineStart.UNDISPATCHED) { dispatch(f.ctx, DocumentEnglishWire.PATH, selection(f.selected)) }; freshEntered.await()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { f.wire.drain() }
                assertFalse(draining.isCompleted)
                assertNull(f.ctx.routes.match(DocumentEnglishWire.PATH)); assertNull(f.ctx.routes.match(DocumentEnglishWire.RETAINED_PATH))
                retainedRelease.complete(Unit); assertEquals(f.cid.value, retained.await()["receiptCid"])
                assertFalse(draining.isCompleted)
                freshRelease.complete(Unit); assertEquals(200, fresh.await().status); draining.await()
                assertEquals(1, f.hooks.factoryCalls.get(), "Only the explicitly requested fresh synthetic inspection may construct a reader")
            } finally { retainedRelease.complete(Unit); freshRelease.complete(Unit); close(f) }
        }
    }
}
