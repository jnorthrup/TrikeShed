package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EnglishTaxonomyWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val sourceText = "Tenant is a Person. A Person is an Agent."
    private val context = "urn:agreement:pilot"
    private val tenant = "urn:agreement:pilot:Tenant"
    private val person = "urn:agreement:pilot:class:Person"
    private val agent = "urn:agreement:pilot:class:Agent"
    private val token = "synthetic-english-taxonomy-analyst"

    /** Explicit synthetic syntax only: the real receipt producer runs, but no parser executes. */
    private fun syntheticSyntax(source: String): NlpDocument {
        require(source == sourceText)
        val sentences = mutableListOf<NlpSentence>()
        var cursor = 0
        for ((ordinal, quote) in s_["Tenant is a Person.", "A Person is an Agent."].view.withIndex()) {
            val begin = source.indexOf(quote, cursor); require(begin >= 0); cursor = begin + quote.length
            val tokens = Regex("[A-Za-z]+|\\.").findAll(quote).mapIndexed { index, match ->
                val word = match.value
                val pos = when (word.lowercase()) { "is" -> "VBZ"; "a", "an" -> "DT"; "." -> "."; else -> "NN" }
                NlpToken(index + 1, begin + match.range.first, begin + match.range.last + 1,
                    word, if (word == "is") "be" else word.lowercase(), pos, "O")
            }.toList().toSeries()
            val subject = if (ordinal == 0) 1 else 2
            val copula = if (ordinal == 0) 2 else 3
            val head = if (ordinal == 0) 4 else 5
            val edges = mutableListOf(NlpDependency(0, head, "root"), NlpDependency(head, subject, "nsubj"),
                NlpDependency(head, copula, "cop"), NlpDependency(head, tokens.size, "punct"),
                NlpDependency(head, head - 1, "det"))
            if (ordinal == 1) edges.add(NlpDependency(subject, 1, "det"))
            sentences.add(NlpSentence(ordinal, begin, cursor, tokens, edges.toSeries()))
        }
        return NlpDocument(source, sentences.toSeries(), NlpMetadata("synthetic-fixture-no-parser", "EnglishTaxonomyWireTest",
            runtime = _m["dependencyNodeIdentity" j "token-index+copy-count/1", "fixtureAuthority" j "synthetic-bindings-only"]))
    }

    private class Reads { var count = 0 }
    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val wire: DocumentReviewWire, val books: ConstellationNodes, val owner: CompletableJob,
        val inspection: Map<String, Any?>, val reads: Reads, val wal: Path) {
        val inspectionCid: String get() = inspection["receiptCid"] as String
        val initialBoard = ctx.blackboard.snapshot()
    }

    private suspend fun fixture(name: String, parent: Job?): Fixture {
        val root = directory.resolve(name); Files.createDirectories(root)
        val files = JvmFileOperations(); val cas = FileCasStore(files, root.resolve("cas").toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas); val corpus = InMemoryProjectCorpus(); val reads = Reads()
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, root.toFile())
        cas.put(sourceText.encodeToByteArray()); corpus.put("pilot", "agreement.txt", sourceText.encodeToByteArray(), "text/plain")
        val selected = assertNotNull(corpus.document("pilot", "agreement.txt"))
        val producer = DocumentEnglishWire(ctx, corpus) { NlpReader { reads.count++; syntheticSyntax(it) } }
        producer.attach()
        val inspection = try {
            val response = producer.route("POST", DocumentEnglishWire.PATH, jsonOf(_m["project" j selected.project,
                "id" j selected.id, "cid" j selected.cid, "rev" j selected.rev, "seq" j selected.seq,
                "begin" j 0, "end" j sourceText.length]), null)
            assertEquals(200, response.status, response.body); reifyMap(response.body)
        } finally { producer.drain() }
        val docs = root.resolve("documents"); Files.createDirectories(docs)
        Files.writeString(docs.resolve("review-principals.json"), jsonOf(_m["principals" j _l[_m[
            "tokenSha256" j ContentId.of(token.encodeToByteArray()).hex, "id" j "synthetic:english-analyst", "synthetic" j true]]]))
        val books = ConstellationNodes(root.toFile(), ctx.blackboard, scope = scope, cas = cas)
        val wire = DocumentReviewWire.open(ctx, corpus, books)
        return Fixture(ctx, cas, corpus, wire, books, owner, inspection, reads, docs.resolve("review.wal"))
    }

    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        try { f.wire.drain() } finally { try { f.wire.close(); f.books.drain() } finally { f.owner.cancelAndJoin() } }
    }
    private suspend fun request(f: Fixture, suffix: String, body: Map<String, Any?>? = null,
        status: Int = 200, authenticated: Boolean = true): Map<String, Any?> {
        val path = "/api/documents/review" + suffix; val method = if (body == null) "GET" else "POST"
        val raw = "$method $path HTTP/1.1\r\n" + (if (authenticated) "Authorization: Bearer $token\r\n" else "") +
            "\r\n" + (body?.let(::jsonOf) ?: "")
        val route = assertNotNull(f.ctx.routes.match(path.substringBefore('?')))
        val reply = assertNotNull(route.route(method, path, raw, null))
        assertEquals(status, reply.status, reply.body)
        return reifyMap(reply.body)
    }
    private fun declarations(personIri: String = person): List<Map<String, Any?>> = _l[
        _m["id" j "sentence:0", "relation" j "instance", "subjectIri" j tenant,
            "subjectSort" j "individual", "objectIri" j personIri, "objectSort" j "class"],
        _m["id" j "sentence:1", "relation" j "subclass", "subjectIri" j personIri,
            "subjectSort" j "class", "objectIri" j agent, "objectSort" j "class"]]
    private suspend fun prepare(f: Fixture, contextIri: String = context, personIri: String = person) =
        request(f, "/english-taxonomy", _m["inspectionCid" j f.inspectionCid,
            "contextIri" j contextIri, "declarations" j declarations(personIri)])
    private fun rows(value: Any?) = (value as List<*>).map { it as Map<*, *> }
    private suspend fun freeze(f: Fixture, prepared: Map<String, Any?>, status: Int = 200) = request(f, "/snapshot",
        _m["kind" j "taxonomy", "receiptCid" j prepared["receiptCid"], "selection" j rows(prepared["selections"]).map { it["id"] }], status)
    private suspend fun decide(f: Fixture, snapshot: Map<String, Any?>, action: String) = request(f, "/decision",
        _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j snapshot["revision"], "action" j action, "note" j "Explicit synthetic translation review"])
    private suspend fun query(f: Fixture, approved: Map<String, Any?>, target: String = agent, status: Int = 200) =
        request(f, "/taxonomy-plan", _m["snapshotCid" j approved["snapshotCid"], "expectedRevision" j approved["revision"],
            "goal" j _m["predicate" j "instance", "subject" j tenant, "object" j target]], status)
    private fun family(review: Map<String, Any?>) = (review["snapshot"] as Map<*, *>)["family"]

    @Test fun authenticatedTranslationUsesTheRealReceiptAndLedgerWithTwoExactWitnesses(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("flow", coroutineContext[Job])
            try {
                val identity = _m["inspectionCid" j f.inspectionCid]
                request(f, "/english-taxonomy-candidates", identity, status = 401, authenticated = false)
                request(f, "/english-taxonomy", _m["inspectionCid" j f.inspectionCid, "contextIri" j context,
                    "declarations" j declarations()], status = 401, authenticated = false)
                request(f, "/english-taxonomy", _m["inspectionCid" j f.inspectionCid, "contextIri" j context,
                    "declarations" j emptyList<Map<String, Any?>>()], status = 409)
                val candidates = request(f, "/english-taxonomy-candidates", identity)
                assertEquals(true, candidates["eligible"])
                assertEquals("unreviewed-translation-candidates", candidates["authority"])
                val sentences = rows(candidates["sentences"]); assertEquals(2, sentences.size)
                assertEquals(_l["Tenant is a Person.", "A Person is an Agent."], sentences.map { it["quote"] })
                assertEquals(_l["1:0", "2:0"], sentences.map { it["subjectNode"] })
                assertEquals(_l["4:0", "5:0"], sentences.map { it["predicateNode"] })
                val prepared = prepare(f)
                assertEquals("unreviewed", prepared["reviewStatus"]); assertFalse(prepared.containsKey("approvalCid"))
                request(f, "/taxonomy-candidates", _m["receiptCid" j prepared["receiptCid"], "offset" j 0, "limit" j 32], status = 409)
                val declarationBytes = assertNotNull(f.cas.getBounded(ContentId(prepared["translationCid"] as String), EnglishTaxonomyCarrier.MAX_BYTES))
                val declaration = CanonicalCbor.decodeMap(declarationBytes)
                assertEquals("proposed", declaration["reviewStatus"]); assertEquals(f.inspectionCid, declaration["inspectionCid"])
                val source = ContentId(prepared["derivedKifCid"] as String)
                assertContentEquals((prepared["kif"] as String).encodeToByteArray(), assertNotNull(f.cas.getBounded(source, EnglishTaxonomyCarrier.MAX_BYTES)))
                val snapshot = freeze(f, prepared)
                query(f, snapshot, status = 409)
                val draft = decide(f, snapshot, "draft"); query(f, draft, status = 409)
                val session = request(f, "/session")
                assertEquals("synthetic:english-analyst", session["reviewer"]); assertEquals(true, session["synthetic"])
                val approved = decide(f, draft, "approve")
                val walSize = Files.size(f.wal)
                val result = query(f, approved)
                assertEquals("SUPPORTED", result["status"]); assertEquals(true, result["positivePathProven"])
                assertEquals("reviewed-read-only-not-a-sink-permit", result["authority"])
                assertEquals("explicit-reviewed-translation-assumptions-not-English-entailment", (result["englishOrigin"] as Map<*, *>)["authority"])
                assertEquals(approved["decisionCid"], result["approvalCid"])
                val witnesses = rows(result["englishWitnesses"])
                assertEquals(2, witnesses.size); assertEquals(2, witnesses.map { it["selectionId"] }.toSet().size)
                val offered = rows(prepared["selections"]).associateBy { it["selectionId"] }
                for (witness in witnesses) {
                    assertEquals(prepared["translationCid"], witness["translationDecisionCid"])
                    assertEquals(f.inspectionCid, witness["inspectionCid"]); assertEquals(context, witness["contextIri"])
                    val english = witness["englishQuotation"] as Map<*, *>
                    val kif = witness["kifQuotation"] as Map<*, *>
                    assertEquals(sourceText.substring((english["begin"] as Number).toInt(), (english["end"] as Number).toInt()), english["quote"])
                    assertEquals((prepared["kif"] as String).substring((kif["begin"] as Number).toInt(), (kif["end"] as Number).toInt()), kif["quote"])
                    assertEquals(source.value, kif["originalCid"])
                    val expected = assertNotNull(offered[witness["selectionId"]])
                    assertEquals(expected["declared"], witness["declared"]); assertEquals(expected["bindings"], witness["bindings"])
                    assertTrue(rows(witness["bindings"]).any { it["relation"] == "cop" })
                    assertTrue(rows(witness["bindings"]).any { it["relation"] == "nsubj" })
                }
                assertEquals(witnesses.map { it["englishQuotation"] }.toSet(), rows(result["selected"]).map {
                    (((it["value"] as Map<*, *>)["englishOrigin"] as Map<*, *>)["englishQuotation"])
                }.toSet())
                val exported = (result["plan"] as Map<*, *>)["export"] as String
                assertContains(exported, "kif-taxonomy/v1/instance-assertion")
                assertContains(exported, "kif-taxonomy/v1/instance-subclass")
                assertEquals("UNKNOWN", query(f, approved, "urn:agreement:pilot:class:Missing")["status"])
                assertEquals(walSize, Files.size(f.wal), "Reading support must not append decisions or consume approval")
                assertEquals(1, f.reads.count); assertNull(f.ctx.beliefBag)
                assertEquals(f.initialBoard, f.ctx.blackboard.snapshot(), "Translation review and support reads must leave setup facts unchanged")
            } finally { close(f) }
        }
    }

    @Test fun staleEnglishRefusesAnExistingCarrierAtFreezeApprovalAndQuery(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("stale", coroutineContext[Job])
            try {
                val prepared = prepare(f); val snapshot = freeze(f, prepared); val approved = decide(f, snapshot, "approve")
                assertEquals("SUPPORTED", query(f, approved)["status"])
                val changed = "Tenant is a Person. A Person is an Organization.".encodeToByteArray()
                f.cas.put(changed); f.corpus.put("pilot", "agreement.txt", changed, "text/plain")
                request(f, "/english-taxonomy-candidates", _m["inspectionCid" j f.inspectionCid], status = 409)
                freeze(f, prepared, status = 409)
                request(f, "/decision", _m["snapshotCid" j approved["snapshotCid"], "expectedRevision" j approved["revision"],
                    "action" j "approve"], status = 409)
                query(f, approved, status = 409)
                val historical = request(f, "?snapshotCid=" + approved["snapshotCid"])
                assertEquals(false, historical["current"]); assertEquals(false, historical["sourceCurrent"])
                assertEquals(approved["decisionCid"], historical["decisionCid"])
                assertNotNull(f.cas.getBounded(ContentId(prepared["translationCid"] as String), EnglishTaxonomyCarrier.MAX_BYTES))
                assertEquals(1, f.reads.count)
            } finally { close(f) }
        }
    }

    @Test fun prepareDoesNotSupersedeApprovalButSameContextFreezeDoesAndOtherContextsStaySeparate(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("families", coroutineContext[Job])
            try {
                val original = prepare(f); val first = freeze(f, original); val approved = decide(f, first, "approve")
                val walSize = Files.size(f.wal)
                val changed = prepare(f, personIri = "urn:agreement:pilot:class:DeclaredPerson")
                assertNotEquals(original["translationCid"], changed["translationCid"])
                assertEquals(walSize, Files.size(f.wal)); assertEquals("SUPPORTED", query(f, approved)["status"])
                val replacement = freeze(f, changed)
                assertEquals(family(first), family(replacement)); assertNotEquals(first["snapshotCid"], replacement["snapshotCid"])
                query(f, approved, status = 409); query(f, replacement, status = 409)
                val updatedApproval = decide(f, replacement, "approve")
                assertEquals("SUPPORTED", query(f, updatedApproval)["status"])
                val otherPrepared = prepare(f, contextIri = "urn:agreement:other")
                val otherSnapshot = freeze(f, otherPrepared)
                assertNotEquals(family(replacement), family(otherSnapshot))
                assertNotEquals(changed["contextKey"], otherPrepared["contextKey"])
                assertEquals("SUPPORTED", query(f, updatedApproval)["status"])
                val otherApproval = decide(f, otherSnapshot, "approve")
                val otherResult = query(f, otherApproval)
                assertEquals("SUPPORTED", otherResult["status"])
                assertEquals("urn:agreement:other", (otherResult["englishOrigin"] as Map<*, *>)["contextIri"])
                assertEquals("SUPPORTED", query(f, updatedApproval)["status"])
                assertEquals(1, f.reads.count); assertNull(f.ctx.beliefBag)
            } finally { close(f) }
        }
    }
}
