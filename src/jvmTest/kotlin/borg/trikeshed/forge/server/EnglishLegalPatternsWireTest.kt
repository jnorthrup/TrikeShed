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
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.Base64
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
    private fun fixture(name: String, parent: Job?, quantityCopy: Boolean = false,
        reader: ((String) -> NlpDocument)? = null, retainedRows: MutableMap<String, ProjectDoc>? = null): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas); val corpus = InMemoryProjectCorpus(); val hooks = Hooks()
        val live = object : ProjectCorpus by corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                hooks.lookup?.invoke()
                return if (retainedRows != null) retainedRows[project + "\u0000" + id] else corpus.document(project, id)
            }
        }
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val english = DocumentEnglishWire(ctx, live) { NlpReader { hooks.parserCalls++; reader?.invoke(it) ?: parsed(it, quantityCopy) } }
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

    private fun program(variables: Series<String>, patterns: Series<Triple<String, String, String>>): Map<*, *> {
        val nodes = mutableListOf(LcncNode("select", SparqlGraph.SELECT)); val wires = mutableListOf<LcncWire>()
        for ((i, variable) in variables.view.withIndex()) {
            nodes.add(LcncNode("v$i", SparqlGraph.VARIABLE, _m["name" j variable, "ord" j i.toString()]))
            wires.add(LcncWire("v$i", "variable", "select", "variables"))
        }
        for ((i, pattern) in patterns.view.withIndex()) {
            nodes.add(LcncNode("p$i", SparqlGraph.PATTERN, _m["subject" j pattern.first,
                "predicate" j pattern.second, "object" j pattern.third]))
            wires.add(LcncWire("p$i", "pattern", "select", "patterns"))
        }
        return reifyStrict(LcncProgramConfix.toJson(LcncProgram("Legal language query", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true)))) as Map<*, *>
    }
    private fun predicate(name: String) = "<${EnglishLegalGraph.VOCAB}$name>"
    private fun thirtyDays() = program(s_["?quote", "?status", "?sentence", "?source"], s_[
        Triple("?d", predicate("kind"), "\"duration-language-candidate\""),
        Triple("?d", predicate("integerValue"), "\"30\"^^<${RdfVocab.XSD}integer>"), Triple("?d", predicate("unitLemma"), "\"day\""),
        Triple("?d", predicate("quote"), "?quote"), Triple("?d", predicate("status"), "?status"),
        Triple("?d", predicate("sentence"), "?s"), Triple("?s", predicate("quote"), "?sentence"), Triple("?d", predicate("sourceText"), "?source")])
    private fun unknownQuantities() = program(s_["?quote", "?reason"], s_[
        Triple("?d", predicate("status"), "\"unresolved-quantity-candidate\""), Triple("?d", predicate("quote"), "?quote"),
        Triple("?d", predicate("unresolved"), "?reason")])
    private suspend fun query(f: Fixture, cid: ContentId, value: Map<*, *> = thirtyDays(), mode: String = "current", status: Int = 200): Map<*, *> {
        val route = assertNotNull(f.ctx.routes.match(EnglishLegalQueryWire.PATH))
        val reply = assertNotNull(route.route("POST", EnglishLegalQueryWire.PATH,
            jsonOf(_m["inspectionCid" j cid.value, "mode" j mode, "program" j value]), null))
        assertEquals(status, reply.status, reply.body); return reifyStrict(reply.body) as Map<*, *>
    }
    private fun rows(result: Map<*, *>) = ((result["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>

    @Test fun originalCanonicalGuardReceiptsReplayThroughExistingWireAndQueryWithoutAParser(): Unit = runBlocking {
        for (case in s_["G03", "G04", "G07", "G13"].view) {
            val baseline = guardBaseline(case)
            val retainedRows = mutableMapOf<String, ProjectDoc>()
            val f = fixture("attachments-$case", coroutineContext[Job], reader = { error("Original retained replay cannot parse") }, retainedRows = retainedRows)
            try {
                fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/english-guards/$name")).use { it.readBytes() }
                val originalBytes = Base64.getMimeDecoder().decode(resource("$case-inspection.cbor.b64"))
                val inspection = f.cas.put(originalBytes)
                assertEquals(baseline.originalBaselineInspectionCid, inspection)
                assertEquals(baseline.sourceCid, f.cas.put(resource("$case-source.txt")))
                val original = EnglishObservationGraph.read(f.cas, inspection)
                assertEquals("4.6.0", original.parsed.metadata?.runtime?.get("packageVersion"))
                assertEquals(baseline.text, original.source.text)
                for (row in assertNotNull(original.sourceSnapshots)) retainedRows[row.project + "\u0000" + row.id] = row
                val candidate = request(f, inspection)
                assertEquals(baseline.sourceCid.value, candidate["extractedTextCid"])
                assertEquals(inspection.value, candidate["inspectionCid"])
                val section = candidate["guardAttachments"] as Map<*, *>
                assertEquals(EnglishGuardAttachments.KIND, section["kind"])
                val guard = (section["sentences"] as List<*>).single() as Map<*, *>
                assertEquals(when(case) { "G03" -> null; "G07" -> "COORDINATED"; else -> "NEGATED" }, guard["normConditionIssue"])
                val syntax = program(s_["?head", "?relation", "?issue", "?inspection"], s_[
                    Triple("?a", predicate("kind"), "\"adverbial-attachment-observation\""),
                    Triple("?a", predicate("subordinateNode"), "?node"), Triple("?node", predicate("word"), "?head"),
                    Triple("?a", predicate("attachmentRelation"), "?relation"), Triple("?a", predicate("normConditionIssue"), "?issue"),
                    Triple("?a", predicate("inspectionReceipt"), "?inspection")])
                val answer = query(f, inspection, syntax)
                val bindings = rows(answer)
                assertEquals((guard["attachments"] as List<*>).size, bindings.size)
                assertTrue(bindings.all { ((it as Map<*, *>)["inspection"] as Map<*, *>)["value"] == "urn:sha256:${inspection.hex}" })
                if (case == "G07") assertEquals(_s["approves", "Trustee"], bindings.map { ((it as Map<*, *>)["head"] as Map<*, *>)["value"] }.toSet())
                if (case == "G13") assertTrue(bindings.any { ((it as Map<*, *>)["relation"] as Map<*, *>)["value"] == "advcl:without" })
                assertEquals(candidate["receiptCid"], (answer["source"] as Map<*, *>)["candidateCid"])
                assertEquals(0, f.hooks.parserCalls)
                val row = retainedRows.values.single()
                retainedRows[row.project + "\u0000" + row.id] = row.copy(cid = ContentId.of("Changed source.".encodeToByteArray()).value,
                    rev = "2-changed", seq = row.seq + 1)
                query(f, inspection, syntax, status = 409)
                assertEquals(bindings.size, rows(query(f, inspection, syntax, mode = "snapshot")).size)
                assertEquals(0, f.hooks.parserCalls)
                assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            } finally { close(f) }
        }
    }

    @Test fun visualQuantityQueryReturnsExactSourceAndCandidateScopeThroughTheSameOwner(): Unit = runBlocking {
        val f = fixture("legal-quantity-query", coroutineContext[Job])
        try {
            val inspection = inspect(f); val candidate = request(f, inspection)
            val result = query(f, inspection); val row = rows(result).single() as Map<*, *>
            fun value(key: String) = (row[key] as Map<*, *>)["value"] as String
            assertEquals("within thirty (30) business days", value("quote")); assertEquals(text, value("sentence"))
            assertEquals("lexical-integer-candidate", value("status"))
            assertEquals("urn:sha256:" + (candidate["extractedTextCid"] as String).removePrefix("sha256:"), value("source"))
            val source = result["source"] as Map<*, *>
            assertEquals(candidate["receiptCid"], source["candidateCid"]); assertEquals(inspection.value, source["inspectionCid"])
            assertEquals(EnglishLegalGraph.graph(ContentId(candidate["receiptCid"] as String)), source["namedGraph"])
            assertEquals("unreviewed", source["reviewStatus"]); assertEquals("not-established", source["producerAuthority"])
            assertEquals("cooccurrence-only-not-applicability", source["sameSentenceJoin"])
            assertNotNull(f.cas.getBounded(ContentId(source["candidateCid"] as String), EnglishLegalPatternsWire.MAX_RESPONSE))
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishLegalQueryWire.TYPE])
            val node = LcncNode("query", EnglishLegalQueryWire.TYPE, _m["inspectionCid" j inspection.value, "mode" j "snapshot", "program" j jsonOf(thirtyDays())])
            assertEquals(1, rows(runner.run(node, emptyMap())).size)
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["mode" j null]) }
            assertEquals(1, f.hooks.parserCalls); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        } finally { close(f) }
        assertNull(f.ctx.routes.match(EnglishLegalQueryWire.PATH)); assertNull(f.ctx.lcncRunners[EnglishLegalQueryWire.TYPE])
    }

    @Test fun unknownQuantitiesHaveNoInventedIntegerAndStaleCurrentSourcesRefuse(): Unit = runBlocking {
        val f = fixture("legal-unknown-query", coroutineContext[Job], quantityCopy = true)
        try {
            val inspection = inspect(f)
            assertTrue(rows(query(f, inspection)).isEmpty(), "Unresolved quantities cannot acquire integerValue=30")
            val unresolved = rows(query(f, inspection, unknownQuantities()))
            assertTrue(unresolved.any { (((it as Map<*, *>)["reason"] as Map<*, *>)["value"] as String) == "copy-node-quantity-scope-not-established" })
            f.corpus.put("legal", "agreement.txt", "Changed source.".encodeToByteArray())
            query(f, inspection, unknownQuantities(), status = 409)
            assertTrue(rows(query(f, inspection, unknownQuantities(), mode = "snapshot")).isNotEmpty())
        } finally { close(f) }
    }

    @Test fun queryDrainCompletesAcceptedPatternWorkWithoutRecursiveAdmissionDeadlock(): Unit = runBlocking {
        val f = fixture("legal-query-drain", coroutineContext[Job])
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        try {
            val inspection = inspect(f)
            f.hooks.lookup = { if (once.compareAndSet(false, true)) {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } }
            val pending = (0 until 4).map { async(start = CoroutineStart.UNDISPATCHED) { query(f, inspection) } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val draining = async { f.english.drain() }; yield(); assertFalse(draining.isCompleted)
            release.countDown(); for (query in pending) assertEquals(1, rows(query.await()).size); draining.await()
        } finally { release.countDown(); close(f) }
    }

    @Test fun oversizedLegalGraphRefusesWithoutOmittingObservedFields(): Unit = runBlocking {
        val f = fixture("legal-query-budget", coroutineContext[Job])
        try {
            val inspection = inspect(f, text + " filler".repeat(1200))
            val result = query(f, inspection, status = 409)
            assertTrue((result["error"] as String).contains("smaller text range"))
        } finally { close(f) }
    }
}
