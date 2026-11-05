package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real authenticated route, retained source and durable review owner; no supplied approval callback. */
class ReviewedTaxonomyQuadQueryWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val token = "synthetic-quad-query-analyst"

    private class CountingCas(private val delegate: FileCasStore) : CasStore(), BoundedCasReader {
        var puts = 0
        override val durability get() = delegate.durability
        override fun put(bytes: ByteArray): ContentId { puts++; return delegate.put(bytes) }
        override fun get(cid: ContentId): ByteArray? = delegate.get(cid)
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = delegate.getBounded(cid, maxBytes)
        override fun sync() = delegate.sync()
    }

    private class WatchedCorpus(val delegate: InMemoryProjectCorpus) : ProjectCorpus by delegate {
        var reads = 0
        var changeAt = Int.MAX_VALUE
        override suspend fun document(project: String, id: String): ProjectDoc? {
            if (id == "source.kif" && ++reads == changeAt)
                delegate.put(project, id, "(subclass Changed DuringQuery)".encodeToByteArray(), "text/plain")
            return delegate.document(project, id)
        }
    }

    private class Fixture(val ctx: ModuleContext, val cas: CountingCas, val corpus: WatchedCorpus,
        val wire: DocumentReviewWire, val books: ConstellationNodes, val owner: CompletableJob,
        val wal: Path, val goal: Map<String, Any?>)

    private suspend fun request(ctx: ModuleContext, suffix: String, body: Map<String, Any?>,
        status: Int = 200, auth: Boolean = true, method: String = "POST"): Map<String, Any?> {
        val path = "/api/documents/review/$suffix"
        val raw = "$method $path HTTP/1.1\r\n" + (if (auth) "Authorization: Bearer $token\r\n" else "") + "\r\n" + jsonOf(body)
        val reply = assertNotNull(ctx.routes.match(path)?.route(method, path, raw, null))
        assertEquals(status, reply.status, reply.body)
        return reifyMap(reply.body)
    }

    private suspend fun fixture(name: String, parent: Job?,
        text: String = "(subclass Unused Other)\n(subclass A B)\n(subclass B C)", target: String = "C"): Fixture {
        val root = directory.resolve(name); Files.createDirectories(root)
        val files = JvmFileOperations(); val cas = CountingCas(FileCasStore(files, root.resolve("cas").toString()))
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, root.toFile())
        val corpus = WatchedCorpus(InMemoryProjectCorpus())
        corpus.delegate.put("test", "source.kif", text.encodeToByteArray(), "text/plain")
        val source = DocumentCurationLegos.projectSource(cas, corpus, assertNotNull(corpus.document("test", "source.kif")))
        val receipt = cas.put(DocumentCuratorCodec.encode(DocumentCurationRecord(source, null, null,
            "synthetic", emptySeriesOf(), emptySeriesOf())))
        val ids = DocumentTaxonomyCandidate.projection(source).map { it["id"] as String }
        val docs = root.resolve("documents"); Files.createDirectories(docs)
        Files.writeString(docs.resolve("review-principals.json"), jsonOf(_m["principals" j _l[_m[
            "tokenSha256" j ContentId.of(token.encodeToByteArray()).hex, "id" j "synthetic:quad-analyst", "synthetic" j true]]]))
        val books = ConstellationNodes(root.toFile(), ctx.blackboard, scope = scope, cas = cas)
        val wire = DocumentReviewWire.open(ctx, corpus, books)
        val snapshot = request(ctx, "snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j ids])
        val approved = request(ctx, "decision", _m["snapshotCid" j snapshot["snapshotCid"],
            "expectedRevision" j snapshot["revision"], "action" j "approve"])
        return Fixture(ctx, cas, corpus, wire, books, owner, docs.resolve("review.wal"),
            _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j approved["revision"],
                "goal" j _m["predicate" j "subclass", "subject" j "A", "object" j target], "offset" j 0, "limit" j 1])
    }

    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        try { f.wire.drain() } finally { try { f.wire.close(); f.books.drain() } finally { f.owner.cancelAndJoin() } }
    }

    private fun rows(raw: Any?) = (raw as List<*>).map { it as Map<*, *> }
    private fun bindings(query: Map<*, *>) = rows(((query["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"])
    private fun program(report: Map<*, *>, patternCount: Int = 6) =
        reviewedProofQueryProgram(report, patternCount = patternCount)

    @Test fun currentHostQueryUsesOffPagePremisesAndKeepsPhysicalAndReviewedAuthoritySeparate(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("plain", coroutineContext[Job])
            try {
                val plain = request(f.ctx, "taxonomy-plan", f.goal)
                assertFalse("quadQuery" in plain)
                assertEquals("(subclass Unused Other)", ((rows(plain["selected"]).single()["quotation"]) as Map<*, *>)["quote"])
                val plan = plain["plan"] as Map<*, *>
                assertNull(f.cas.get(ContentId(plan["exportCid"] as String)))
                assertNull(f.cas.get(ContentId(plain["readFrameCid"] as String)))
                val writes = f.cas.puts; val wal = Files.size(f.wal); val board = f.ctx.blackboard.snapshot()
                val rete = f.ctx.rete.snapshot().size
                val global = QuadStore().add("<urn:unrelated>", "<urn:approved>", "true")
                val result = request(f.ctx, "taxonomy-plan", f.goal + _m["program" j program(plain)])
                assertEquals(plain, result - "quadQuery")
                val query = result["quadQuery"] as Map<*, *>
                assertEquals("request-local", query["persistence"])
                assertEquals("authenticated-reviewed-read-not-a-sink-permit", query["authority"])
                val physical = query["physicalSource"] as Map<*, *>
                assertEquals("quad-store", physical["selected"])
                assertEquals("not-established", physical["reviewAuthority"])
                val provenance = query["provenance"] as Map<*, *>
                for (key in s_["snapshotCid", "revision", "readCid", "readFrameCid", "approvalCid", "decisionCid", "sourceRevision", "reviewer", "synthetic"].view)
                    assertEquals(result[key], provenance[key], key)
                assertEquals(plan["frameId"], provenance["planFrameId"])
                assertEquals(123L, (provenance["checkedAsOf"] as Number).toLong())
                val found = bindings(query).sortedBy { ((it["ordinal"] as Map<*, *>)["value"] as String).toInt() }
                assertEquals(2, found.size)
                assertEquals(_l["(subclass A B)", "(subclass B C)"], found.map { (it["kifQuote"] as Map<*, *>)["value"] })
                for (item in found) {
                    assertEquals("uri", (item["source"] as Map<*, *>)["type"])
                    assertEquals(RdfVocab.XSD + "integer", (item["ordinal"] as Map<*, *>)["datatype"])
                    assertEquals("literal", (item["rule"] as Map<*, *>)["type"])
                }
                assertEquals(writes, f.cas.puts); assertEquals(wal, Files.size(f.wal)); assertEquals(board, f.ctx.blackboard.snapshot())
                assertEquals(rete, f.ctx.rete.snapshot().size); assertNull(f.ctx.beliefBag); assertEquals(1, global.size)
                val repeat = request(f.ctx, "taxonomy-plan", f.goal)
                assertEquals(plain, repeat)
                val public = SparqlWire(SparqlSurface(global)).route("POST", "/sparql/visual",
                    jsonOf(_m["dataset" j ReviewedTaxonomyProofGraph.DATASET, "program" j program(plain)]), null)
                assertEquals(400, assertNotNull(public).status, "The protected graph is not a public dataset selector")
            } finally { close(f) }
        }
    }

    @Test fun querySyntaxAndBudgetRefusalsHaveNoPartialBindingsOrAuthorityFallback(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("refusals", coroutineContext[Job])
            try {
                val plain = request(f.ctx, "taxonomy-plan", f.goal); val valid = program(plain)
                request(f.ctx, "taxonomy-plan", f.goal + _m["program" j valid], 401, auth = false)
                request(f.ctx, "taxonomy-plan", f.goal + _m["program" j valid], 405, method = "GET")
                for (key in s_["dataset", "reviewer", "report", "exportCid", "credential"].view)
                    request(f.ctx, "taxonomy-plan", f.goal + _m["program" j valid, key j "forged"], 409)
                val nodes = valid["nodes"] as List<*>
                val first = (nodes.first() as Map<*, *>).mapKeys { it.key as String }
                val forgedNode = first + _m["params" j _m["credential" j "forged"]]
                val forged = valid + _m["nodes" j (_l[forgedNode] + nodes.drop(1))]
                var dispatches = 0
                f.ctx.lcncRunners["test.forbidden"] = LcncNodeRunner { _, _ -> dispatches++; emptyMap() }
                val dispatch = valid + _m["nodes" j (_l[first + _m["type" j "test.forbidden"]] + nodes.drop(1))]
                val writes = f.cas.puts; val wal = Files.size(f.wal)
                for (bad in _l[null, "not-a-program", forged, dispatch,
                    valid + _m["controls" j _m["inspectionOnly" j false]],
                    valid + _m["dataset" j "quad-store+rete"]]) {
                    val refusal = request(f.ctx, "taxonomy-plan", f.goal + _m["program" j bad], 400)
                    assertEquals("reviewed_taxonomy_query_refused", refusal["error"])
                    assertFalse("results" in refusal || "quadQuery" in refusal)
                }
                val limit = request(f.ctx, "taxonomy-plan", f.goal + _m["program" j program(plain, 9)], 413)
                assertEquals(413, (limit["queryStatus"] as Number).toInt()); assertEquals("visual_query_refused", limit["code"])
                assertFalse("results" in limit || "quadQuery" in limit)
                assertEquals(writes, f.cas.puts); assertEquals(wal, Files.size(f.wal)); assertEquals(0, dispatches)
                assertEquals(plain, request(f.ctx, "taxonomy-plan", f.goal))
                val unknownGoal = f.goal + _m["goal" j _m["predicate" j "subclass", "subject" j "A", "object" j "Missing"]]
                val unknown = request(f.ctx, "taxonomy-plan", unknownGoal)
                assertEquals("UNKNOWN", unknown["status"])
                val empty = request(f.ctx, "taxonomy-plan", unknownGoal + _m["program" j program(unknown)])
                assertEquals("UNKNOWN", empty["status"], "The query never changes the existing typed proof status")
                assertTrue(bindings(empty["quadQuery"] as Map<*, *>).isEmpty())
                assertEquals(writes, f.cas.puts); assertEquals(wal, Files.size(f.wal))
            } finally { close(f) }
        }
    }

    @Test fun optionalSourceProfileLimitDoesNotChangePlainReportAcceptance(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("quote-limit", coroutineContext[Job], "(subclass" + " ".repeat(4100) + " A B)", "B")
            try {
                val plain = request(f.ctx, "taxonomy-plan", f.goal)
                assertEquals("SUPPORTED", plain["status"])
                val writes = f.cas.puts; val wal = Files.size(f.wal)
                val refusal = request(f.ctx, "taxonomy-plan", f.goal + _m["program" j program(plain)], 413)
                assertEquals("reviewed_taxonomy_source_profile_limit", refusal["code"])
                assertFalse("results" in refusal || "quadQuery" in refusal)
                assertEquals(plain, request(f.ctx, "taxonomy-plan", f.goal))
                assertEquals(writes, f.cas.puts); assertEquals(wal, Files.size(f.wal))
            } finally { close(f) }
        }
    }

    @Test fun finalReadRefusesSourceChangeDuringQueryAndWithdrawnApproval(): Unit = runBlocking {
        withTimeout(20_000) {
            val f = fixture("during", coroutineContext[Job])
            try {
                f.corpus.reads = 0
                val plain = request(f.ctx, "taxonomy-plan", f.goal)
                val plainReads = f.corpus.reads
                assertTrue(plainReads > 0)
                val writes = f.cas.puts; val wal = Files.size(f.wal)
                f.corpus.reads = 0; f.corpus.changeAt = plainReads + 1
                val refusal = request(f.ctx, "taxonomy-plan", f.goal + _m["program" j program(plain)], 409)
                assertTrue(f.corpus.reads >= f.corpus.changeAt, "Mutation occurs only in the extra final read")
                assertFalse("results" in refusal || "quadQuery" in refusal)
                assertEquals(writes, f.cas.puts); assertEquals(wal, Files.size(f.wal))
            } finally { close(f) }
            val withdrawn = fixture("withdrawn", coroutineContext[Job])
            try {
                val plain = request(withdrawn.ctx, "taxonomy-plan", withdrawn.goal)
                request(withdrawn.ctx, "decision", _m["snapshotCid" j withdrawn.goal["snapshotCid"],
                    "expectedRevision" j withdrawn.goal["expectedRevision"], "action" j "reject"])
                val refusal = request(withdrawn.ctx, "taxonomy-plan", withdrawn.goal + _m["program" j program(plain)], 409)
                assertFalse("results" in refusal || "quadQuery" in refusal)
            } finally { close(withdrawn) }
        }
    }
}

internal fun reviewedProofQueryProgram(report: Map<*, *>, english: Boolean = false,
    patternCount: Int = if (english) 8 else 6): Map<String, Any?> {
    fun term(name: String) = "<${ReviewedTaxonomyProofGraph.VOCAB}$name>"
    fun rows(raw: Any?) = (raw as List<*>).map { it as Map<*, *> }
    val graph = ReviewedTaxonomyProofGraph.graphIri(report["readFrameCid"] as String)
    val plan = report["plan"] as Map<*, *>
    val proof = rows(plan["proofs"]).firstOrNull()
    val resource = proof?.let { ReviewedTaxonomyProofGraph.proofIri(graph, it["nodeId"] as String) }
        ?: graph + "/proof/absent"
    val triples = _l[Triple("<$resource>", term("premiseUse"), "?use"), Triple("?use", term("source"), "?source"),
        Triple("?use", term("ordinal"), "?ordinal"), Triple("?use", term("rule"), "?rule"),
        Triple("?source", term("kifQuotation"), "?kif"), Triple("?kif", term("quote"), "?kifQuote")] +
        if (english) _l[Triple("?source", term("englishQuotation"), "?english"),
            Triple("?english", term("quote"), "?englishQuote")] else emptyList()
    val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT)); val wires = mutableListOf<LcncWire>()
    val variables = _l["?ordinal", "?source", "?rule", "?kifQuote"] + if (english) _l["?englishQuote"] else emptyList()
    for ((index, variable) in variables.withIndex()) {
        nodes.add(LcncNode("v$index", SparqlGraph.VARIABLE, _m["name" j variable, "ord" j index.toString()]))
        wires.add(LcncWire("v$index", "variable", "select", "variables"))
    }
    repeat(patternCount) { index ->
        val triple = triples[index % triples.size]
        nodes.add(LcncNode("p$index", SparqlGraph.PATTERN, _m["subject" j triple.first,
            "predicate" j triple.second, "object" j triple.third, "graph" j "<$graph>"]))
        wires.add(LcncWire("p$index", "pattern", "select", "patterns"))
    }
    return reifyMap(LcncProgramConfix.toJson(LcncProgram("reviewed query", nodes.toSeries(), wires.toSeries(),
        controls = LcncConfixControls(inspectionOnly = true))))
}
