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
    private suspend fun request(f: Fixture, receipts: List<String>, mode: String = "current", topics: Any? = null, status: Int = 200): Map<*, *> {
        val route = assertNotNull(f.ctx.routes.match(EnglishCorpusWire.PATH))
        val response = assertNotNull(route.route("POST", EnglishCorpusWire.PATH,
            jsonOf(_m["receiptCids" j receipts, "mode" j mode, "topics" j topics]), null))
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
}
