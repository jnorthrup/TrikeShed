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
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class EnglishCorpusWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val english: DocumentEnglishWire, val owner: CompletableJob, val casRoot: java.io.File)

    private fun parsed(text: String): NlpDocument {
        val words = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]").findAll(text).toList()
        val tokens = words.mapIndexed { i, word -> NlpToken(i + 1, word.range.first, word.range.last + 1,
            word.value, word.value.lowercase(), "NN", "O") }.toSeries()
        val edges = (0 until tokens.size).map { i -> NlpDependency(if (i == 0) 0 else 1, i + 1, if (i == 0) "root" else "dep") }.toMutableList()
        if (tokens.size >= 4) edges.add(NlpDependency(3, 3, "conj", 0, 1))
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges.toSeries())], NlpMetadata("fixture", "EnglishCorpusWireTest"))
    }
    private fun fixture(name: String, parent: Job?): Fixture {
        val files = JvmFileOperations(); val casRoot = directory.resolve(name).toFile(); val cas = FileCasStore(files, casRoot.path)
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus()
        val english = DocumentEnglishWire(ctx, corpus) { NlpReader(::parsed) }
        english.attach(); return Fixture(ctx, cas, corpus, english, owner, casRoot)
    }
    private fun put(f: Fixture, id: String, text: String) {
        f.cas.put(text.encodeToByteArray()); f.corpus.put("legal", id, text.encodeToByteArray())
    }
    private suspend fun inspect(f: Fixture, id: String, begin: Int, end: Int): Map<*, *> {
        val row = assertNotNull(f.corpus.document("legal", id))
        val response = f.english.route("POST", DocumentEnglishWire.PATH, jsonOf(_m["project" j row.project, "id" j row.id,
            "cid" j row.cid, "rev" j row.rev, "seq" j row.seq, "begin" j begin, "end" j end]), null)
        assertEquals(200, response.status, response.body); return reifyStrict(response.body) as Map<*, *>
    }
    private suspend fun request(f: Fixture, receipts: List<String>, mode: String = "current", topics: Any? = null,
                                status: Int = 200, extra: Map<String, Any?> = emptyMap()): Map<*, *> {
        val route = assertNotNull(f.ctx.routes.match(EnglishCorpusWire.PATH))
        val response = assertNotNull(route.route("POST", EnglishCorpusWire.PATH,
            jsonOf(_m["receiptCids" j receipts, "mode" j mode, "topics" j topics] + extra), null))
        assertEquals(status, response.status, response.body); return reifyStrict(response.body) as Map<*, *>
    }
    private suspend fun close(f: Fixture) { f.english.drain(); f.owner.complete(); f.owner.join() }

    @Test fun twoVerifiedReceiptsProduceExactConcordanceAndOptionalReproducibleTopics(): Unit = runBlocking {
        val f = fixture("two-documents", coroutineContext[Job])
        try {
            val a = "Tenant must not assign unless Owner consents."
            val b = "A grant pays audit costs and finance reconciles audit costs."
            put(f, "agreement.txt", a); put(f, "grant.md", b)
            val first = inspect(f, "agreement.txt", 0, a.length); val second = inspect(f, "grant.md", 0, b.length)
            val ids = _l[first["receiptCid"] as String, second["receiptCid"] as String]
            val result = request(f, ids)
            assertNull(result["topics"]); assertNull(result["topicCid"])
            val terms = (result["terms"] as List<*>).map { it as Map<*, *> }
            assertEquals(1, (terms.single { it["lemma"] == "not" }["frequency"] as Number).toInt())
            assertEquals(2, (terms.single { it["lemma"] == "audit" }["frequency"] as Number).toInt())
            val expected = _l[first, second].sumOf { ((it["counts"] as Map<*, *>)["tokens"] as Number).toInt() }
            assertEquals(expected, (result["rawTokenCount"] as Number).toInt(), "Enhanced graph copies cannot add lexical occurrences")
            val sentences = (result["sentences"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
            for (raw in result["occurrences"] as List<*>) {
                val occurrence = raw as Map<*, *>; val sentence = sentences.getValue(occurrence["sentenceId"])
                val offset = (sentence["begin"] as Number).toInt()
                assertEquals(occurrence["word"], (sentence["quote"] as String).substring(
                    (occurrence["begin"] as Number).toInt() - offset, (occurrence["end"] as Number).toInt() - offset))
            }
            val source = result["source"] as Map<*, *>; assertEquals("not-reverified", source["parserExecution"])
            assertEquals("not-established", source["producerAuthority"]); assertEquals(false, result["countsAreEvidenceConfidence"])
            assertEquals("unreviewed", result["reviewStatus"]); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            for (key in s_["receiptCid", "corpusCid", "matrixCid"].view) {
                val cid = ContentId(result[key] as String); assertEquals(cid, ContentId.of(assertNotNull(f.cas.getBounded(cid, EnglishCorpusWire.MAX_RESPONSE))))
            }
            val config = _m["topics" j 2, "iterations" j 10, "seed" j 7, "alpha" j .1, "beta" j .01]
            val topics = request(f, ids, topics = config); val repeated = request(f, ids.reversed(), topics = config)
            assertEquals(topics["receiptCid"], repeated["receiptCid"]); assertEquals(topics["topicCid"], repeated["topicCid"])
            val model = topics["topics"] as Map<*, *>; assertEquals("descriptive-statistics-only", model["authority"])
            assertEquals((model["tokenCount"] as Number).toInt(), (model["topicTotals"] as List<*>).sumOf { (it as Number).toInt() })
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishCorpusWire.TYPE])
            val viaNode = runner.run(LcncNode("corpus", EnglishCorpusWire.TYPE, _m["topics" j jsonOf(config)]),
                _m["receiptCids" j ids, "mode" j "current", "topics" j null])
            assertNull(viaNode["topics"], "Explicit null must override an authored LDA parameter")
        } finally { close(f) }
        assertNull(f.ctx.routes.match(EnglishCorpusWire.PATH)); assertNull(f.ctx.lcncRunners[EnglishCorpusWire.TYPE])
    }

    @Test fun overlapsDuplicatesStaleSourcesAndInvalidTopicRequestsRefuse(): Unit = runBlocking {
        val f = fixture("corpus-refusals", coroutineContext[Job])
        try {
            val text = "Tenant must not assign unless Owner consents."
            put(f, "agreement.txt", text)
            val full = inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String
            val partial = inspect(f, "agreement.txt", 0, 22)["receiptCid"] as String
            request(f, _l[full, full], status = 409)
            val overlap = request(f, _l[full, partial], status = 409)
            assertTrue((overlap["error"] as String).contains("Overlapping"))
            request(f, _l[full], topics = _m["topics" j 17, "iterations" j 10, "seed" j 1, "alpha" j .1, "beta" j .01], status = 409)
            put(f, "agreement.txt", "Changed source.")
            request(f, _l[full], status = 409)
            val historical = request(f, _l[full], mode = "snapshot")
            assertEquals(true, (historical["source"] as Map<*, *>)["historical"])
        } finally { close(f) }
    }

    @Test fun wiredOptionalTopicsReachTheRegisteredCorpusRunnerWithPresencePrecedence(): Unit = runBlocking {
        val f = fixture("corpus-topics-port", coroutineContext[Job])
        try {
            val text = "Tenant must not assign unless Owner consents."
            put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            val config = _m["topics" j 2, "iterations" j 10, "seed" j 7, "alpha" j .1, "beta" j .01]
            val withTopics = request(f, ids, topics = config)
            val withoutTopics = request(f, ids)
            suspend fun wired(topicInputs: Map<String, Any?>, authored: String = "null"): Map<String, Any?> {
                val nodes = mutableListOf<LcncNode>()
                nodes.add(LcncNode("receipts", "json.value", _m["value" j jsonOf(ids)]))
                nodes.add(LcncNode("mode", "text.value", _m["value" j "current"]))
                val wires = mutableListOf<LcncWire>()
                wires.add(LcncWire("receipts", "value", "corpus", "receiptCids"))
                wires.add(LcncWire("mode", "value", "corpus", "mode"))
                for ((index, input) in topicInputs.entries.withIndex()) {
                    val id = "topic-input-$index"
                    nodes.add(LcncNode(id, "json.value", _m["value" j jsonOf(input.value)]))
                    wires.add(LcncWire(id, "value", "corpus", input.key))
                }
                nodes.add(LcncNode("corpus", EnglishCorpusWire.TYPE, _m["topics" j authored]))
                nodes.add(LcncNode("topics", LcncContracts.SCOPE_OUT, _m["name" j "topics", "kind" j "json"]))
                nodes.add(LcncNode("topicCid", LcncContracts.SCOPE_OUT, _m["name" j "topicCid", "kind" j "json"]))
                wires.add(LcncWire("corpus", "topics", "topics", "value"))
                wires.add(LcncWire("corpus", "topicCid", "topicCid", "value"))
                val result = LcncRunner(PureNodes.registry { 123L } + f.ctx.lcncRunners)
                    .runProcedure(LcncProgram("corpus-topics-port", nodes.toSeries(), wires.toSeries()))
                val output = result.nodeOutputs.getValue("corpus")
                for (port in s_["topics", "topicCid"].view) {
                    assertTrue(result.returns.containsKey(port), "Explicit null remains a wired output")
                    assertEquals(output[port], result.returns[port])
                }
                return output
            }
            val configured = wired(_m["topics?" j config])
            assertNotNull(configured["topics"]); assertNotNull(configured["topicCid"])
            assertEquals(withTopics, configured, "The palette's optional port must match the actual HTTP corpus build and CIDs")
            assertEquals(withoutTopics, wired(_m["topics?" j null], jsonOf(config)), "Wired optional null must override authored LDA")
            assertEquals(withoutTopics, wired(_m["topics?" j config, "topics" j null], jsonOf(config)), "Canonical null precedes the optional alias")
            assertEquals(withTopics, wired(emptyMap(), jsonOf(config)), "Unwired bounded authored JSON decoding remains available")
            assertEquals(withoutTopics, wired(emptyMap()), "Omitted optional topics remain null")
            for (invalid in s_[config + _m["topics" j 17], config + _m["extra" j true], "invalid"].view)
                assertFailsWith<IllegalArgumentException> { wired(_m["topics?" j invalid], jsonOf(config)) }
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        } finally { close(f) }
    }

    @Test fun optionalViewPreservesLegacyRequestsAndHttpAndLcncRefuseExplicitNullOrUnknown(): Unit = runBlocking {
        val f = fixture("corpus-view", coroutineContext[Job])
        try {
            val text = "Tenant must not have consent."
            put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            val default = request(f, ids)
            assertFalse("view" in default)
            val all = request(f, ids, extra = _m["view" j "all-lexical"])
            for (key in s_["corpusCid", "matrixCid", "terms", "sentences", "occurrences"].view) assertEquals(default[key], all[key])
            val content = request(f, ids, extra = _m["view" j "content"])
            assertEquals("content", content["view"])
            assertEquals(EnglishCorpusView.CONTENT.policy().toMap(), (content["matrix"] as Map<*, *>)["policy"])
            val included = (content["terms"] as List<*>).map { (it as Map<*, *>)["lemma"] }
            assertTrue("must" in included && "not" in included && "have" !in included)
            assertEquals(default["rawTokenCount"], content["rawTokenCount"])
            assertEquals(default["sentences"], content["sentences"])
            for (invalid in s_[null, "unknown", 1].view) request(f, ids, status = 409, extra = _m["view" j invalid])

            val runner = assertNotNull(f.ctx.lcncRunners[EnglishCorpusWire.TYPE])
            val legacy = runner.run(LcncNode("legacy", EnglishCorpusWire.TYPE), _m["receiptCids" j ids, "mode" j "current", "topics" j null])
            assertEquals(default, legacy, "Omitted LCNC view must preserve the original response and receipt bytes")
            val node = LcncNode("content", EnglishCorpusWire.TYPE, _m["view" j "content"])
            val authored = runner.run(node, _m["receiptCids" j ids, "mode" j "current", "topics" j null])
            assertEquals(content, authored)
            for (invalid in s_[null, "unknown"].view) assertFailsWith<IllegalArgumentException> {
                runner.run(node, _m["receiptCids" j ids, "mode" j "current", "topics" j null, "view" j invalid])
            }
            val overridden = runner.run(node, _m["receiptCids" j ids, "mode" j "current", "topics" j null, "view" j "all-lexical"])
            assertEquals(all, overridden)
            val inputs = _m["receiptCids" j ids, "mode" j "current", "topics" j null]
            val defaults = node.copy(params = _m["view" j "all-lexical", "in:view" j "content"])
            assertEquals(content, runner.run(defaults, inputs), "in:view must precede the plain parameter")
            assertEquals(content, runner.run(node.copy(params = _m["view" j "all-lexical"]),
                inputs + _m["view?" j "content"]), "The optional view port must reach the same source-pinned content policy as HTTP")
            assertEquals(all, runner.run(defaults, inputs + _m["view?" j "all-lexical"]),
                "A wired optional view must override authored defaults")
            assertEquals(all, runner.run(defaults, inputs + _m["view" j "all-lexical", "view?" j "content"]),
                "The normalized port must precede its optional alias")
            for (invalid in s_[null, "unknown"].view) assertFailsWith<IllegalArgumentException> {
                runner.run(defaults, inputs + _m["view?" j invalid])
            }
            assertFailsWith<IllegalArgumentException> {
                runner.run(defaults, inputs + _m["view" j null, "view?" j "content"])
            }
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        } finally { close(f) }
    }

    @Test fun samplerWorkAndFullResponseBoundsRefuseWithoutPublishingPartialDerivedReceipts(): Unit = runBlocking {
        val f = fixture("corpus-budgets", coroutineContext[Job])
        try {
            val text = "word ".repeat(14_000); put(f, "large.txt", text)
            val receipts = (0 until 7).map { i -> inspect(f, "large.txt", i * 10_000, (i + 1) * 10_000)["receiptCid"] as String }
            fun retained() = f.casRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(f.casRoot).path }.toSet()
            val before = retained()
            val budget = request(f, receipts, topics = _m["topics" j 16, "iterations" j 100, "seed" j 1, "alpha" j .1, "beta" j .01], status = 409)
            assertTrue((budget["error"] as String).contains("conditional weights")); assertEquals(before, retained())
            val output = request(f, receipts, status = 409)
            assertTrue((output["error"] as String).contains("4MiB")); assertEquals(before, retained())
        } finally { close(f) }
    }

    /** The pre-query payload construction, using the verified snapshot's typed observations. */
    private fun legacyPayload(f: Fixture, ids: List<String>, mode: String, topics: EnglishLdaConfig?): Map<String, Any?> {
        val snapshots = ids.sorted().map { EnglishObservationGraph.read(f.cas, ContentId(it)) }.toSeries()
        val core = EnglishCorpus.build(snapshots α { snapshot ->
            val range = snapshot.inventory["range"] as Map<*, *>
            EnglishObservationReceipt(snapshot.receiptCid, snapshot.source.originalCid, snapshot.source.extractedTextCid,
                (range["begin"] as Number).toInt(), (range["end"] as Number).toInt(), (range["totalLength"] as Number).toInt(),
                snapshot.selection, snapshot.parsed.sentences α { sentence ->
                    EnglishObservationSentence(sentence.index, sentence.begin, sentence.end,
                        snapshot.parsed.text.substring(sentence.begin, sentence.end), sentence.tokens α { token ->
                            EnglishObservationToken(token.index, token.begin, token.end, token.word, token.lemma, token.tag)
                        })
                })
        })
        val model = topics?.let(core::lda)
        return core.toMap() + _m["corpusCid" j core.cid.value, "receiptCids" j (snapshots α { it.receiptCid.value }).toList(),
            "topics" j model?.toMap(), "topicCid" j model?.cid?.value,
            "source" j _m["selected" j "english-receipt-corpus", "mode" j mode, "historical" j (mode == "snapshot"),
                "currentness" j if (mode == "current") "checked-before-and-after-build" else "not-checked",
                "reviewStatus" j "unreviewed", "parserExecution" j "not-reverified", "producerAuthority" j "not-established",
                "sources" j (snapshots α { it.descriptor(mode) }).toList()]]
    }

    @Test fun optionalSearchPreservesLegacyBytesAndBindsOnlyItsQueryReceipt(): Unit = runBlocking {
        val f = fixture("corpus-query-identities", coroutineContext[Job])
        try {
            val texts = _m["first.txt" j "Tenant must not have audit audit.", "second.txt" j "Owner keeps audit records."]
            for ((id, text) in texts) put(f, id, text)
            val ids = texts.map { (id, text) -> inspect(f, id, 0, text.length)["receiptCid"] as String }
            val config = EnglishLdaConfig(2, 10, 7, .1, .01)
            val legacy = request(f, ids, topics = config.toMap())
            val oldPayload = legacyPayload(f, ids, "current", config)
            val expected = CanonicalCbor.encodeMap(oldPayload)
            val oldResponse = assertNotNull(f.ctx.routes.match(EnglishCorpusWire.PATH)).route("POST", EnglishCorpusWire.PATH,
                jsonOf(_m["receiptCids" j ids, "mode" j "current", "topics" j config.toMap()]), null)
            assertEquals(jsonOf(oldPayload + _m["receiptCid" j ContentId.of(expected).value]), assertNotNull(oldResponse).body,
                "Omitted query also retains the pre-query HTTP JSON bytes")
            assertFalse("search" in legacy)
            assertEquals(ContentId.of(expected).value, legacy["receiptCid"], "Omitted query retains the pre-query canonical receipt identity")
            assertContentEquals(expected, assertNotNull(f.cas.getBounded(ContentId(legacy["receiptCid"] as String), EnglishCorpusWire.MAX_RESPONSE)))
            val query = _m["terms" j _l["audit"], "limit" j 32]
            val searched = request(f, ids, topics = config.toMap(), extra = _m["query" j query])
            val repeated = request(f, ids.reversed(), topics = config.toMap(), extra = _m["query" j query])
            assertEquals(searched, repeated)
            val search = searched["search"] as Map<*, *>
            assertEquals("trikeshed.english-corpus-search.v1", search["schema"])
            assertEquals("english-selected-corpus-retrieval", search["dataset"])
            assertEquals(reifyStrict(jsonOf(query)), search["query"]); assertEquals("lexical-match-only", search["authority"])
            assertEquals(false, search["countsAreEvidenceConfidence"])
            for (key in s_["corpusCid", "matrixCid", "receiptCids", "retrieval", "source"].view) assertEquals(searched[key], search[key])
            assertEquals((searched["matrix"] as Map<*, *>)["policy"], search["policy"])
            for (key in s_["corpusCid", "matrixCid", "topicCid", "terms", "topics"].view) assertEquals(legacy[key], searched[key])
            assertNotEquals(legacy["receiptCid"], searched["receiptCid"])
            val hits = (search["hits"] as List<*>).map { it as Map<*, *> }
            assertEquals(2, hits.size)
            assertEquals(3, hits.sumOf { (it["matches"] as List<*>).size }, "Every original occurrence stays visible, not an extra evidence unit")
            for (hit in hits) {
                assertEquals("lexical-match-only", hit["authority"]); assertTrue((hit["score"] as Number).toDouble().isFinite())
                val sentence = hit["sentence"] as Map<*, *>; val selection = sentence["selection"] as Map<*, *>
                assertTrue(sentence["receiptCid"] in ids)
                assertEquals(selection["cid"], hit["originalCid"]); assertEquals(hit["originalCid"], hit["extractedTextCid"])
                val original = texts.getValue(selection["id"] as String)
                assertEquals(original.substring((sentence["begin"] as Number).toInt(), (sentence["end"] as Number).toInt()), sentence["quote"])
                for (raw in hit["matches"] as List<*>) {
                    val match = raw as Map<*, *>; assertEquals(sentence["receiptCid"], match["receiptCid"])
                    assertEquals(sentence["id"], match["sentenceId"]); assertEquals("audit", match["term"])
                    assertEquals(match["word"], original.substring((match["begin"] as Number).toInt(), (match["end"] as Number).toInt()))
                }
            }
            val limited = request(f, ids, topics = config.toMap(), extra = _m["query" j (query + _m["limit" j 1])])
            assertEquals(1, ((limited["search"] as Map<*, *>)["hits"] as List<*>).size)
            assertNotEquals(searched["receiptCid"], limited["receiptCid"])
            for (key in s_["corpusCid", "matrixCid", "topicCid"].view) assertEquals(searched[key], limited[key])
            val reopened = FileCasStore(JvmFileOperations(), f.casRoot.path)
            val cid = ContentId(searched["receiptCid"] as String)
            val bytes = assertNotNull(reopened.getBounded(cid, EnglishCorpusWire.MAX_RESPONSE))
            assertEquals(cid, ContentId.of(bytes)); val retained = CanonicalCbor.decodeMap(bytes)
            assertEquals(searched["search"], reifyStrict(jsonOf(retained["search"])))
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty()); assertEquals(0, f.ctx.rete.snapshot().size)
            assertNull(f.ctx.beliefBag); assertNull(f.ctx.documentReview)
        } finally { close(f) }
    }

    @Test fun actualWiredQueryPresencePrecedesAliasesAndBoundedAuthoredJson(): Unit = runBlocking {
        val f = fixture("corpus-query-ports", coroutineContext[Job])
        try {
            val text = "Tenant must not assign unless Owner consents."; put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            val a = _m["terms" j _l["not"], "limit" j 8]; val b = _m["terms" j _l["unless"], "limit" j 1]
            suspend fun wired(inputs: Map<String, Any?>, params: Map<String, String> = emptyMap()): Map<String, Any?> {
                val nodes = mutableListOf(LcncNode("ids", "json.value", _m["value" j jsonOf(ids)]),
                    LcncNode("mode", "text.value", _m["value" j "current"]), LcncNode("corpus", EnglishCorpusWire.TYPE, params),
                    LcncNode("result", LcncContracts.SCOPE_OUT, _m["name" j "search", "kind" j "json"]))
                val wires = mutableListOf(LcncWire("ids", "value", "corpus", "receiptCids"), LcncWire("mode", "value", "corpus", "mode"),
                    LcncWire("corpus", "search", "result", "value"))
                for ((i, input) in inputs.entries.withIndex()) {
                    nodes.add(LcncNode("input-$i", "json.value", _m["value" j jsonOf(input.value)]))
                    wires.add(LcncWire("input-$i", "value", "corpus", input.key))
                }
                val output = LcncRunner(PureNodes.registry { 123L } + f.ctx.lcncRunners)
                    .runProcedure(LcncProgram("corpus-query-ports", nodes.toSeries(), wires.toSeries()))
                val result = output.nodeOutputs.getValue("corpus")
                if ("search" in result) assertEquals(result["search"], output.returns["search"])
                return result
            }
            val expected = request(f, ids, extra = _m["query" j a])
            assertEquals(expected, wired(_m["query?" j a]))
            assertEquals(expected, wired(emptyMap(), _m["query" j jsonOf(a)]))
            assertEquals(expected, wired(emptyMap(), _m["query" j jsonOf(b), "in:query" j jsonOf(a)]))
            assertEquals(expected, wired(_m["query?" j a], _m["query" j "not-json"]))
            assertEquals(expected, wired(_m["query" j a, "query?" j b]))
            assertEquals(request(f, ids), wired(emptyMap()), "Omitted query has no search payload")
            for (inputs in s_[_m["query?" j null], _m["query" j null, "query?" j a]].view)
                assertFailsWith<IllegalArgumentException> { wired(inputs, _m["query" j jsonOf(a)]) }
            assertFailsWith<IllegalArgumentException> { wired(emptyMap(), _m["query" j jsonOf(a), "in:query" j "null"]) }
            assertFailsWith<IllegalArgumentException> { wired(emptyMap(), _m["query" j "x".repeat(65_537)]) }
        } finally { close(f) }
    }

    @Test fun searchUsesTheSamePolicyAndExplicitNoMatchDatasetWithoutChangingTopics(): Unit = runBlocking {
        val f = fixture("corpus-query-policy", coroutineContext[Job])
        try {
            val text = "Tenant must not have audit audit unless Owner consents."; put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            suspend fun search(term: String, view: String): Map<*, *> = request(f, ids,
                extra = _m["view" j view, "query" j _m["terms" j _l[term], "limit" j 32]])
            val all = search("have", "all-lexical"); val filtered = search("have", "content")
            assertEquals(1, ((all["search"] as Map<*, *>)["hits"] as List<*>).size)
            assertEquals(0, ((filtered["search"] as Map<*, *>)["hits"] as List<*>).size)
            assertNotEquals(all["matrixCid"], filtered["matrixCid"])
            for (term in s_["not", "unless"].view) {
                val result = search(term, "content"); val search = result["search"] as Map<*, *>
                assertEquals((result["matrix"] as Map<*, *>)["policy"], search["policy"])
                assertEquals(1, (search["hits"] as List<*>).size); assertNull(result["topics"]); assertNull(result["topicCid"])
            }
            for (term in s_["AUDIT", "not-in-this-dataset"].view) {
                val result = search(term, "content"); val search = result["search"] as Map<*, *>
                assertEquals(emptyList<Any?>(), search["hits"])
                assertEquals(ids, search["receiptCids"]); assertEquals("lexical-match-only", search["authority"])
                assertEquals(false, search["countsAreEvidenceConfidence"])
            }
        } finally { close(f) }
    }

    @Test fun currentSearchChecksBeforeAfterWorkAndAfterRetentionWhileSnapshotsStayHistorical(): Unit = runBlocking {
        for (mutateAt in s_[1, 2, 3].view) {
            val f = fixture("corpus-query-current-$mutateAt", coroutineContext[Job]); var reads = 0
            val changed = object : ProjectCorpus by f.corpus {
                override suspend fun document(project: String, id: String): ProjectDoc? {
                    if (++reads == mutateAt) f.corpus.put(project, id, "Changed source.".encodeToByteArray())
                    return f.corpus.document(project, id)
                }
            }
            val wire = EnglishCorpusWire(f.ctx, changed)
            try {
                val text = "Tenant must not assign."; put(f, "agreement.txt", text)
                val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
                val query = _m["terms" j _l["not"], "limit" j 32]
                fun body(mode: String) = jsonOf(_m["receiptCids" j ids, "mode" j mode, "topics" j null, "query" j query])
                val failed = wire.route("POST", EnglishCorpusWire.PATH, body("current"), null)
                assertEquals(409, failed.status); assertTrue(failed.body.contains("stale")); assertFalse(failed.body.contains("\"hits\""))
                assertEquals(mutateAt, reads)
                val historical = wire.route("POST", EnglishCorpusWire.PATH, body("snapshot"), null)
                assertEquals(200, historical.status, historical.body); assertEquals(mutateAt, reads)
                val output = reifyStrict(historical.body) as Map<*, *>; val search = output["search"] as Map<*, *>
                assertEquals(true, (search["source"] as Map<*, *>)["historical"])
                assertEquals("not-checked", (search["source"] as Map<*, *>)["currentness"])
                assertEquals(1, (search["hits"] as List<*>).size)
                assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            } finally { wire.drain(); close(f) }
        }
    }

    @Test fun malformedAndOversizedSearchesRefuseWithoutPartialHitsOrNewDerivedReceipts(): Unit = runBlocking {
        val f = fixture("corpus-query-bounds", coroutineContext[Job])
        try {
            val text = "Tenant must not assign."; put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            fun retained() = f.casRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(f.casRoot).path }.toSet()
            val boundary = request(f, ids, extra = _m["query" j _m["terms" j (0 until 64).map { "not" }, "limit" j 256]])
            assertEquals(1, ((boundary["search"] as Map<*, *>)["hits"] as List<*>).size)
            val before = retained(); val valid = _m["terms" j _l["not"], "limit" j 32]
            val invalid = s_[null, "not", valid + _m["extra" j true], valid + _m["terms" j emptyList<String>()],
                valid + _m["terms" j (0..64).map { "not" }], valid + _m["terms" j _l[null]],
                valid + _m["terms" j _l[" "]], valid + _m["limit" j 0], valid + _m["limit" j 257],
                valid + _m["limit" j 1.5], valid + _m["limit" j "32"]]
            for (query in invalid.view) {
                val refused = request(f, ids, status = 409, extra = _m["query" j query])
                assertTrue("error" in refused); assertFalse("search" in refused); assertEquals(before, retained())
            }
            val large = "word ".repeat(9_000); put(f, "large.txt", large)
            val receipts = (0 until 5).map { i -> inspect(f, "large.txt", i * 10_000, minOf((i + 1) * 10_000, large.length))["receiptCid"] as String }
            val plain = request(f, receipts)
            assertFalse("search" in plain, "The underlying corpus itself fits before adding the complete search witness set")
            val inspected = retained()
            val refused = request(f, receipts, status = 409, extra = _m["query" j _m["terms" j _l["word"], "limit" j 256]])
            assertTrue((refused["error"] as String).contains("4MiB")); assertFalse("search" in refused)
            assertEquals(inspected, retained(), "Full response bound is checked before retaining any partial derived result")
        } finally { close(f) }
    }

    @Test fun offeredLocalCorpusPresetRunsTheRegisteredOwnerAndReturnsStatisticsWithSearch(): Unit = runBlocking {
        val f = fixture("corpus-query-preset", coroutineContext[Job])
        try {
            val text = "Tenant must not assign unless Owner consents."; put(f, "agreement.txt", text)
            val ids = _l[inspect(f, "agreement.txt", 0, text.length)["receiptCid"] as String]
            val program = EnglishCorpusPreset.build(ids, "current", _l["not"], 8)
            assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
            val result = LcncRunner(PureNodes.registry { 123L } + f.ctx.lcncRunners).runProcedure(program)
            val output = result.nodeOutputs.getValue("corpus")
            assertEquals(output["search"], result.returns["search"]); assertEquals(output["receiptCid"], result.returns["receiptCid"])
            assertEquals(output["terms"], result.returns["terms"]); assertEquals(output["source"], result.returns["source"])
            val search = result.returns["search"] as Map<*, *>; assertEquals(1, (search["hits"] as List<*>).size)
            assertEquals("current", (search["source"] as Map<*, *>)["mode"])
            assertEquals("unreviewed", (search["source"] as Map<*, *>)["reviewStatus"])
            assertNull(output["topics"]); assertNull(output["topicCid"])
            assertTrue(program.nodes.view.none { it.type.startsWith("vm.") || it.type == "chat" })
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty()); assertEquals(0, f.ctx.rete.snapshot().size)
        } finally { close(f) }
    }

}
