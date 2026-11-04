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

class EnglishLegalPatternsWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private class Hooks { var lookup: (() -> Unit)? = null; var parserCalls = 0 }
    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val english: DocumentEnglishWire, val owner: CompletableJob, val hooks: Hooks)
    private val text = "Tenant shall not pay rent within thirty (30) business days unless Owner consents."

    /** These explicit dependency fixtures establish retained-boundary behavior, not parser accuracy. */
    private fun parsed(source: String, quantityCopy: Boolean): NlpDocument {
        val matches = Regex("[A-Za-z]+|[0-9]+|[^\\sA-Za-z0-9]").findAll(source).toList()
        val tokens = matches.mapIndexed { i, match ->
            val word = match.value; val lemma = when (word) { "days" -> "day"; "consents" -> "consent"; else -> word.lowercase() }
            val tag = when (word) { "shall" -> "MD"; "pay" -> "VB"; "thirty", "30", "60" -> "CD"; else -> "NN" }
            NlpToken(i + 1, match.range.first, match.range.last + 1, word, lemma, tag, if (tag == "CD") "NUMBER" else "O")
        }.toSeries()
        fun at(word: String) = tokens.view.single { it.word == word }.index
        val action = at("pay"); val unit = at("days"); val number = tokens.view.single { it.word.all(Char::isDigit) }.index
        val edges = tokens.view.associate { token -> (token.index j NlpDependency(if (token.index == action) 0 else action,
            token.index, if (token.index == action) "root" else "dep")).pair }.toMutableMap()
        fun edge(governor: Int, dependent: Int, relation: String) { edges[dependent] = NlpDependency(governor, dependent, relation) }
        edge(action, at("Tenant"), "nsubj"); edge(action, at("shall"), "aux"); edge(action, at("not"), "neg")
        edge(action, at("rent"), "obj"); edge(action, unit, "obl"); edge(unit, at("within"), "case")
        edge(unit, number, "nummod"); edge(number, at("thirty"), "compound"); edge(number, at("("), "punct"); edge(number, at(")"), "punct")
        edge(unit, at("business"), "amod"); edge(action, at("consents"), "advcl")
        edge(at("consents"), at("unless"), "mark"); edge(at("consents"), at("Owner"), "nsubj")
        val expanded = edges.values.toMutableList()
        expanded.add(NlpDependency(action, action, "conj", 0, 1)); expanded.add(NlpDependency(action, at("shall"), "aux", 1, 0))
        expanded.add(NlpDependency(action, at("Tenant"), "nsubj", 1, 0)); expanded.add(NlpDependency(action, at("rent"), "obj", 1, 0))
        if (quantityCopy) { expanded.add(NlpDependency(unit, unit, "conj", 0, 1)); expanded.add(NlpDependency(unit, number, "nummod", 1, 0)) }
        return NlpDocument(source, s_[NlpSentence(0, 0, source.length, tokens, expanded.toSeries())],
            NlpMetadata("fixture", "EnglishLegalPatternsWireTest"))
    }
    private fun fixture(name: String, parent: Job?, quantityCopy: Boolean = false): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas); val corpus = InMemoryProjectCorpus(); val hooks = Hooks()
        val live = object : ProjectCorpus by corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? { hooks.lookup?.invoke(); return corpus.document(project, id) }
        }
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val english = DocumentEnglishWire(ctx, live) { NlpReader { hooks.parserCalls++; parsed(it, quantityCopy) } }
        english.attach(); return Fixture(ctx, cas, corpus, english, owner, hooks)
    }
    private suspend fun inspect(f: Fixture, source: String = text): ContentId {
        f.cas.put(source.encodeToByteArray()); f.corpus.put("legal", "agreement.txt", source.encodeToByteArray())
        val row = assertNotNull(f.corpus.document("legal", "agreement.txt"))
        val reply = f.english.route("POST", DocumentEnglishWire.PATH, jsonOf(_m["project" j row.project, "id" j row.id,
            "cid" j row.cid, "rev" j row.rev, "seq" j row.seq, "begin" j 0, "end" j source.length]), null)
        assertEquals(200, reply.status, reply.body); return ContentId((reifyStrict(reply.body) as Map<*, *>)["receiptCid"] as String)
    }
    private suspend fun request(f: Fixture, cid: ContentId, mode: String = "current", status: Int = 200, extras: Map<String, Any?> = emptyMap()): Map<*, *> {
        val route = assertNotNull(f.ctx.routes.match(EnglishLegalPatternsWire.PATH))
        val reply = assertNotNull(route.route("POST", EnglishLegalPatternsWire.PATH,
            jsonOf(_m["receiptCid" j cid.value, "mode" j mode] + extras), null))
        assertEquals(status, reply.status, reply.body); return reifyStrict(reply.body) as Map<*, *>
    }
    private suspend fun close(f: Fixture) { f.english.drain(); f.owner.complete(); f.owner.join() }

    @Test fun retainedModalRolesDurationAndNegatedGuardStayUnreviewedAndExact(): Unit = runBlocking {
        val f = fixture("legal-patterns", coroutineContext[Job])
        try {
            val inspection = inspect(f); val result = request(f, inspection)
            assertEquals("trikeshed.english-legal-patterns.v1", result["schema"]); assertEquals(inspection.value, result["inspectionCid"])
            assertEquals("unreviewed", result["reviewStatus"]); assertEquals("not-established", result["producerAuthority"])
            assertEquals("not-reverified", result["parserExecution"]); assertEquals(false, result["countsAreEvidenceConfidence"])
            val sentence = (result["sentences"] as List<*>).single() as Map<*, *>
            assertEquals(text, sentence["quote"])
            assertTrue("guard-scope-not-established" in sentence["unresolved"] as List<*>)
            assertTrue("negation-scope-not-established" in sentence["unresolved"] as List<*>)
            val actions = (sentence["modalActions"] as List<*>).map { it as Map<*, *> }
            assertEquals(_s["4:0", "4:1"], actions.map { (it["action"] as Map<*, *>)["id"] }.toSet())
            for (action in actions) {
                assertEquals("parser-role-candidate", action["status"])
                assertEquals("Tenant", (((action["subjects"] as List<*>).single() as Map<*, *>)["node"] as Map<*, *>)["word"])
            }
            val duration = (sentence["durations"] as List<*>).single() as Map<*, *>
            assertEquals(30, (duration["integerValue"] as Number).toInt()); assertEquals("within thirty (30) business days", duration["quote"])
            assertEquals(duration["quote"], text.substring((duration["begin"] as Number).toInt(), (duration["end"] as Number).toInt()))
            assertEquals(2, (duration["numberReadings"] as List<*>).size)
            assertFalse(result.containsKey("rules")); assertFalse(result.containsKey("facts")); assertFalse(result.containsKey("admitted"))
            val receipt = ContentId(result["receiptCid"] as String)
            val bytes = assertNotNull(f.cas.getBounded(receipt, EnglishLegalPatternsWire.MAX_RESPONSE))
            assertEquals(receipt, ContentId.of(bytes)); assertEquals(inspection.value, CanonicalCbor.decodeMap(bytes)["inspectionCid"])
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishLegalPatternsWire.TYPE])
            val node = LcncNode("patterns", EnglishLegalPatternsWire.TYPE, _m["receiptCid" j inspection.value, "mode" j "snapshot"])
            val viaNode = runner.run(node, emptyMap()); assertEquals(inspection.value, viaNode["inspectionCid"])
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["mode" j null]) }
            assertEquals(1, f.hooks.parserCalls); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            assertNotNull(LcncNodeKey.of(EnglishLegalPatternsWire.TYPE))
            assertNotNull(LcncContracts.find(EnglishLegalPatternsWire.TYPE))
        } finally { close(f) }
        assertNull(f.ctx.routes.match(EnglishLegalPatternsWire.PATH)); assertNull(f.ctx.lcncRunners[EnglishLegalPatternsWire.TYPE])
    }

    @Test fun copiedQuantityBindingsAndConflictingNumeralsRemainUnresolved(): Unit = runBlocking {
        val f = fixture("quantity-copy", coroutineContext[Job], quantityCopy = true)
        try {
            val result = request(f, inspect(f))
            val sentence = (result["sentences"] as List<*>).single() as Map<*, *>
            val duration = (sentence["durations"] as List<*>).single() as Map<*, *>
            assertNull(duration["integerValue"]); assertEquals("unresolved-quantity-candidate", duration["status"])
            assertTrue("copy-node-quantity-scope-not-established" in duration["unresolved"] as List<*>)
            assertEquals(2, (duration["numberDependencies"] as List<*>).size)
        } finally { close(f) }
        val conflict = fixture("quantity-conflict", coroutineContext[Job])
        try {
            val result = request(conflict, inspect(conflict, text.replace("(30)", "(60)")))
            val duration = ((result["sentences"] as List<*>).single() as Map<*, *>)["durations"] as List<*>
            assertNull((duration.single() as Map<*, *>)["integerValue"])
            assertTrue("conflicting-number-forms" in (duration.single() as Map<*, *>)["unresolved"] as List<*>)
        } finally { close(conflict) }
    }

    @Test fun staleAndLateCurrentnessAndUnexpectedFieldsRefuseWithoutClaimingApproval(): Unit = runBlocking {
        val f = fixture("legal-currentness", coroutineContext[Job])
        try {
            val inspection = inspect(f)
            request(f, inspection, status = 409, extras = _m["approved" j true])
            var samples = 0
            f.hooks.lookup = { if (++samples == 3) f.corpus.put("legal", "agreement.txt", "Changed source.".encodeToByteArray()) }
            val refused = request(f, inspection, status = 409)
            assertTrue((refused["error"] as String).contains("stale")); assertFalse(refused.containsKey("receiptCid"))
            val historical = request(f, inspection, mode = "snapshot")
            assertEquals(true, (historical["source"] as Map<*, *>)["historical"])
            assertEquals(text, ((historical["sentences"] as List<*>).single() as Map<*, *>)["quote"])
            assertEquals(1, f.hooks.parserCalls)
        } finally { close(f) }
    }
}
