package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class DocumentEnglishWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    /** Deliberately labelled fixture reader: these checks prove the source/wire boundary, not NLP accuracy. */
    private fun parsed(text: String): NlpDocument {
        val words = Regex("\\S+").findAll(text).toList()
        val tokens = words.mapIndexed { i, word ->
            NlpToken(i + 1, word.range.first, word.range.last + 1, word.value, word.value.lowercase(), "NN", "O")
        }.toSeries()
        val dependencies = (0 until tokens.size).toSeries() α { i -> NlpDependency(if (i == 0) 0 else 1, i + 1, if (i == 0) "root" else "dep") }
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, dependencies)],
            NlpMetadata("fixture", "DocumentEnglishWireTest"))
    }

    private class Fixture(val ctx: ModuleContext, val owner: CompletableJob, val cas: FileCasStore, val corpus: InMemoryProjectCorpus)
    private fun fixture(name: String, parent: Job?): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        return Fixture(ctx, owner, cas, InMemoryProjectCorpus())
    }

    private fun put(f: Fixture, id: String, bytes: ByteArray, type: String = "text/plain"): String {
        assertEquals(ContentId.of(bytes), f.cas.put(bytes))
        return f.corpus.put("legal", id, bytes, type)
    }

    private fun input(row: ProjectDoc, begin: Int, end: Int) = _m["project" j row.project, "id" j row.id,
        "cid" j row.cid, "rev" j row.rev, "seq" j row.seq, "begin" j begin, "end" j end]
    private suspend fun request(wire: DocumentEnglishWire, body: Map<String, Any?>, status: Int = 200): Map<String, Any?> {
        val response = wire.route("POST", DocumentEnglishWire.PATH, jsonOf(body), null)
        assertEquals(status, response.status, response.body)
        return reifyMap(response.body)
    }

    @Test fun exactRangeRetainsUnreviewedObservationsWithoutModelOrBag(): Unit = runBlocking {
        val f = fixture("english-range", coroutineContext[Job]); var calls = 0
        val wire = DocumentEnglishWire(f.ctx, f.corpus) { NlpReader { calls++; parsed(it) } }
        try {
            wire.attach()
            val status = reifyMap(wire.route("GET", DocumentEnglishWire.PATH, "", null).body)
            assertEquals(false, status["requiresModel"]); assertEquals(0, calls)
            val text = "HEADER\nTenant may remain unless rent is not due.\nOTHER"
            put(f, "agreement.txt", text.encodeToByteArray())
            val row = assertNotNull(f.corpus.document("legal", "agreement.txt"))
            val begin = text.indexOf("Tenant"); val end = text.indexOf("\nOTHER")
            val result = request(wire, input(row, begin, end))
            assertEquals(1, calls); assertEquals("unreviewed", result["reviewStatus"])
            assertEquals("lexical-and-parser-observations-only", result["authority"])
            val range = result["range"] as Map<*, *>; assertEquals(false, range["complete"])
            val sentence = (result["sentences"] as List<*>).single() as Map<*, *>
            assertEquals(text.substring(begin, end), sentence["quote"])
            assertEquals(begin, (sentence["begin"] as Number).toInt())
            val tokens = sentence["tokens"] as List<*>
            val not = tokens.map { it as Map<*, *> }.single { it["word"] == "not" }
            val at = (not["begin"] as Number).toInt(); assertEquals("not", text.substring(at, (not["end"] as Number).toInt()))
            val cid = ContentId(result["receiptCid"] as String)
            val bytes = assertNotNull(f.cas.getBounded(cid, DocumentEnglishWire.MAX_RECEIPT))
            assertEquals(cid, ContentId.of(bytes))
            val receipt = CanonicalCbor.decodeMap(bytes)
            assertEquals(row.cid, (receipt["source"] as Map<*, *>)["originalCid"])
            assertFalse((receipt["source"] as Map<*, *>).containsKey("text"))
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            request(wire, input(row, begin, end) + _m["rev" j "stale"], 409)
            request(wire, input(row, 0, DocumentEnglishWire.MAX_CHARS + 1), 409)
            assertEquals(1, calls)
            assertEquals(405, wire.route("PUT", DocumentEnglishWire.PATH, "", null).status)
            assertEquals(404, wire.route("GET", DocumentEnglishWire.PATH + "/other", "", null).status)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
        assertNull(f.ctx.routes.match(DocumentEnglishWire.PATH))
    }

    @Test fun sourceChangesDuringOwnedParseRefusePublicationAndDrainWaitsForAcceptedWork(): Unit = runBlocking {
        val f = fixture("english-current", coroutineContext[Job])
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val wire = DocumentEnglishWire(f.ctx, f.corpus) { NlpReader { entered.complete(Unit); release.await(); parsed(it) } }
        try {
            wire.attach(); val text = "Tenant must pay rent."
            put(f, "agreement.md", text.encodeToByteArray())
            val row = assertNotNull(f.corpus.document("legal", "agreement.md"))
            val pending = async { request(wire, input(row, 0, text.length), 409) }
            entered.await()
            val draining = async { wire.drain() }; yield(); assertFalse(draining.isCompleted)
            put(f, "agreement.md", "Changed source.".encodeToByteArray())
            release.complete(Unit)
            assertTrue((pending.await()["error"] as String).contains("revision changed"))
            draining.await(); assertNull(f.ctx.routes.match(DocumentEnglishWire.PATH))
        } finally { release.complete(Unit); wire.drain(); f.owner.complete(); f.owner.join() }
    }

    @Test fun existingExtractionRequiresExactLineageAndNeverCallsMiner(): Unit = runBlocking {
        val f = fixture("english-extracted", coroutineContext[Job]); var calls = 0; var mines = 0
        val corpus = object : ProjectCorpus by f.corpus {
            override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? { mines++; error("Mining must not run") }
        }
        val wire = DocumentEnglishWire(f.ctx, corpus) { NlpReader { calls++; parsed(it) } }
        try {
            val id = "agreement.pdf"; val original = "%PDF-retained-fixture".encodeToByteArray()
            put(f, id, original, "application/pdf")
            val row = assertNotNull(f.corpus.document("legal", id))
            val text = "Tenant must not assign unless Owner consents."
            request(wire, input(row, 0, text.length), 409); assertEquals(0, calls)
            val extractCid = put(f, id + ProjectNodes.EXTRACT_SUFFIX, text.encodeToByteArray())
            put(f, id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX, "${row.cid}\t$extractCid".encodeToByteArray())
            val completion = _m["version" j 1, "originalCid" j row.cid, "originalRev" j row.rev,
                "extractedTextCid" j extractCid, "anchors" j _l[_m["begin" j 0, "end" j text.length, "page" j 1]],
                "unresolved" j _l["table", "diagram"]]
            put(f, id + ProjectNodes.EXTRACTION_SUFFIX, jsonOf(completion).encodeToByteArray())
            val result = request(wire, input(row, 0, text.length)); assertEquals(1, calls); assertEquals(0, mines)
            assertEquals(row.cid, result["originalCid"]); assertEquals(extractCid, result["extractedTextCid"])
            val source = result["source"] as Map<*, *>; assertEquals(1, (source["anchors"] as List<*>).size)
            put(f, id + ProjectNodes.EXTRACTION_SUFFIX, jsonOf(completion + _m["originalRev" j "old"]).encodeToByteArray())
            request(wire, input(row, 0, text.length), 409); assertEquals(1, calls); assertEquals(0, mines)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }

    @Test fun aHeadChangeBetweenSelectionCheckAndSourceLookupCannotSubstituteNewExtraction(): Unit = runBlocking {
        val f = fixture("english-refetch", coroutineContext[Job]); var reads = 0; var calls = 0
        val id = "agreement.pdf"; val text = "Tenant must pay rent."
        put(f, id, "%PDF-old-source".encodeToByteArray(), "application/pdf")
        val selected = assertNotNull(f.corpus.document("legal", id))
        val extractedCid = put(f, id + ProjectNodes.EXTRACT_SUFFIX, text.encodeToByteArray())
        fun completion(cid: String, rev: String) = jsonOf(_m["version" j 1, "originalCid" j cid,
            "originalRev" j rev, "extractedTextCid" j extractedCid, "anchors" j _l[_m["begin" j 0, "end" j text.length, "page" j 1]],
            "unresolved" j _l["table", "diagram"]]).encodeToByteArray()
        put(f, id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX, "${selected.cid}\t$extractedCid".encodeToByteArray())
        put(f, id + ProjectNodes.EXTRACTION_SUFFIX, completion(selected.cid, selected.rev))
        val replacement = "%PDF-new-source".encodeToByteArray(); val replacementCid = ContentId.of(replacement)
        val newOrigin = "${replacementCid.value}\t$extractedCid".encodeToByteArray()
        val newCompletion = completion(replacementCid.value, "2-${replacementCid.hex.take(8)}")
        // Preload the replacement blobs. The racing corpus changes only its head, so any new CAS
        // file created by the request would be a wrongly published inspection receipt.
        for (bytes in s_[replacement, newOrigin, newCompletion].view) f.cas.put(bytes)
        val casRoot = directory.resolve("english-refetch").toFile()
        fun retainedFiles() = casRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(casRoot).path }.toSet()
        val before = retainedFiles()
        val corpus = object : ProjectCorpus by f.corpus {
            override suspend fun document(project: String, doc: String): ProjectDoc? {
                if (doc == id && ++reads == 2) {
                    f.corpus.put(project, id, replacement, "application/pdf")
                    f.corpus.put(project, id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX, newOrigin)
                    f.corpus.put(project, id + ProjectNodes.EXTRACTION_SUFFIX, newCompletion)
                }
                return f.corpus.document(project, doc)
            }
        }
        val wire = DocumentEnglishWire(f.ctx, corpus) { NlpReader { calls++; parsed(it) } }
        try {
            val response = request(wire, input(selected, 0, text.length), 409)
            assertTrue((response["error"] as String).contains("revision changed"))
            assertNull(response["receiptCid"]); assertEquals(0, calls)
            assertEquals(before, retainedFiles())
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }
}
