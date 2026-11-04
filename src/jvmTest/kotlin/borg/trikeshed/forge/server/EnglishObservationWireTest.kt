<<<<<<< HEAD
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
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class EnglishObservationWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val owner: CompletableJob, val source: DocumentSource, val row: ProjectDoc, val receipt: Map<String, Any?>)

    /** Fixture annotations are recorded claims; this test does not claim actual parser execution. */
    private suspend fun fixture(name: String, parent: Job?, copies: Boolean = true, text: String = "Tenant may remain unless rent is not due."): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus(); val cid = cas.put(text.encodeToByteArray())
        corpus.put("legal", "agreement.txt", text.encodeToByteArray())
        val row = assertNotNull(corpus.document("legal", "agreement.txt")); val rev = DocumentRevision(row.project, row.id, row.cid, row.rev, row.seq)
        val source = DocumentSource(cid, cid, text, row.id, "text/plain", "fixture", category = "document", project = rev, extraction = rev)
        val matches = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text).toList()
        val tokens = matches.mapIndexed { i, word -> NlpToken(i + 1, word.range.first, word.range.last + 1,
            word.value, word.value.lowercase(), "NN", "O") }.toSeries()
        val edges = (0 until tokens.size).map { i -> NlpDependency(if (i == 0) 0 else 1, i + 1, if (i == 0) "root" else "dep") }.toMutableList()
        if (copies) { edges.add(NlpDependency(3, 3, "conj", 0, 1)); edges.add(NlpDependency(3, 4, "obj", 1, 0)) }
        val parsed = NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges.toSeries())], NlpMetadata("fixture", "EnglishObservationWireTest"))
        val receipt = EnglishInspection.inspect(source, parsed, 0, text.length) + _m["source" j (DocumentCuratorCodec.source(source) - "text"),
            "selection" j rev.toMap(), "sourceSnapshots" j _l[row.toMap()]]
        return Fixture(ctx, cas, corpus, owner, source, row, receipt)
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
        return reifyStrict(LcncProgramConfix.toJson(LcncProgram("English query", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true)))) as Map<*, *>
    }
    private fun term(local: String) = "<${EnglishObservationGraph.VOCAB}$local>"
    private fun markerProgram(kind: String) = program(s_["?marker", "?quote"], s_[
        Triple("?marker", term("markerKind"), "\"$kind\""), Triple("?marker", term("quote"), "?quote")])
    private suspend fun request(wire: EnglishObservationWire, cid: ContentId, program: Map<*, *>, mode: String = "snapshot", status: Int = 200): Map<*, *> {
        val reply = wire.route("POST", EnglishObservationWire.PATH, jsonOf(_m["receiptCid" j cid.value, "mode" j mode, "program" j program]), null)
        assertEquals(status, reply.status, reply.body); return reifyStrict(reply.body) as Map<*, *>
    }
    private fun bindings(value: Map<*, *>) = ((value["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>

    @Test fun sourceSpannedNegationAndExceptionAreQueryableWithoutLegalTruthOrGlobalMutation(): Unit = runBlocking {
        val f = fixture("observations", coroutineContext[Job]); val wire = EnglishObservationWire(f.ctx, f.corpus)
        val global = QuadStore().add("<urn:unrelated>", "<urn:p>", "<urn:o>")
        try {
            wire.attach(); val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val result = request(wire, cid, markerProgram("negation"), "current")
            val row = bindings(result).single() as Map<*, *>
            fun value(key: String) = (row[key] as Map<*, *>)["value"] as String
            assertEquals("not", value("quote"))
            val marker = "<${value("marker")}>"
            val spans = bindings(request(wire, cid, program(s_["?begin", "?end"], s_[
                Triple(marker, term("begin"), "?begin"), Triple(marker, term("end"), "?end")]))).single() as Map<*, *>
            assertEquals("not", f.source.text.substring(((spans["begin"] as Map<*, *>)["value"] as String).toInt(),
                ((spans["end"] as Map<*, *>)["value"] as String).toInt()))
            val source = result["source"] as Map<*, *>; assertEquals("not-reverified", source["parserExecution"])
            assertEquals("not-established", source["producerAuthority"]); assertEquals("unreviewed", source["reviewStatus"])
            val exception = bindings(request(wire, cid, markerProgram("exception"))).single() as Map<*, *>
            assertEquals("unless", (exception["quote"] as Map<*, *>)["value"])
            val counts = program(s_["?node"], s_[Triple("?node", term("kind"), "\"token\""), Triple("?node", term("word"), "\"remain\"")])
            assertEquals(1, bindings(request(wire, cid, counts)).size, "Source occurrences must not count enhanced copies")
            val copies = program(s_["?node"], s_[Triple("?node", term("word"), "\"remain\"")])
            assertEquals(2, bindings(request(wire, cid, copies)).size)
            val roles = program(s_["?edge"], s_[Triple("?edge", term("relation"), "\"obj\""),
                Triple("?edge", term("governor"), "<urn:trikeshed:english:${cid.hex}/sentence/0/node/3:1>")])
            assertEquals(1, bindings(request(wire, cid, roles)).size)
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishObservationWire.TYPE])
            val viaNode = runner.run(LcncNode("query", EnglishObservationWire.TYPE), _m["receiptCid" j cid.value,
                "mode" j "current", "program" j markerProgram("negation")])
            assertEquals("english-receipt-observations", viaNode["dataset"])
            assertEquals(1, global.size); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            f.corpus.put("legal", f.row.id, "Changed source.".encodeToByteArray())
            request(wire, cid, markerProgram("negation"), "current", 409)
            assertEquals(1, bindings(request(wire, cid, markerProgram("negation"), "snapshot")).size)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
        assertNull(f.ctx.routes.match(EnglishObservationWire.PATH)); assertNull(f.ctx.lcncRunners[EnglishObservationWire.TYPE])
    }

    @Test fun malformedClaimsInjectionAndLegacyCurrentnessRefuse(): Unit = runBlocking {
        val f = fixture("invalid-observations", coroutineContext[Job], copies = false); val wire = EnglishObservationWire(f.ctx, f.corpus)
        try {
            val changed = f.receipt + _m["originalCid" j ContentId.of("forged".encodeToByteArray()).value]
            val bad = f.cas.put(CanonicalCbor.encodeMap(changed)); request(wire, bad, markerProgram("negation"), status = 409)
            val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val injection = program(s_["?x"], s_[Triple("?x", term("kind") + " . ?x ?p ?o", "\"marker\"")])
            request(wire, cid, injection, status = 400)
            val legacy = (f.receipt - "sourceSnapshots") + _m["counts" j ((f.receipt["counts"] as Map<*, *>) - "nodes"),
                "coverage" j ((f.receipt["coverage"] as Map<*, *>) - _s["dependencyNodeIdentity", "parserDependencyNodeIdentity", "parserNodeIdentityReported"]),
                "sentences" j (f.receipt["sentences"] as List<*>).map { raw -> val row = raw as Map<*, *>
                    (row - "nodes") + _m["dependencies" j (row["dependencies"] as List<*>).map { edge ->
                        (edge as Map<*, *>) - _s["governorCopy", "dependentCopy", "governorNode", "dependentNode"]
                    }]
                }]
            val old = f.cas.put(CanonicalCbor.encodeMap(legacy))
            request(wire, old, markerProgram("negation"), "current", 409)
            val historical = request(wire, old, markerProgram("negation"))
            assertEquals("legacy-unreported", (historical["source"] as Map<*, *>)["dependencyNodeIdentity"])
            assertEquals(405, wire.route("DELETE", EnglishObservationWire.PATH, "", null).status)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }

    @Test fun literalControlsRoundTripAndWrongBytesCannotImpersonateAReceipt(): Unit = runBlocking {
        val f = fixture("literal-observations", coroutineContext[Job], copies = false, text = "Tenant\tmay remain\u000Cunless rent is not due.")
        try {
            val bytes = CanonicalCbor.encodeMap(f.receipt); val cid = f.cas.put(bytes)
            val graph = EnglishObservationGraph.project(EnglishObservationGraph.read(f.cas, cid)).toGraph()
            assertTrue(graph.quads.any { (it.o as? RdfTerm.Literal)?.lexical == f.source.text })
            val bad = object : CasStore(), BoundedCasReader {
                override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray = "different bytes".encodeToByteArray()
            }
            assertFailsWith<IllegalArgumentException> { EnglishObservationGraph.read(bad, cid) }
        } finally { f.owner.complete(); f.owner.join() }
    }

    @Test fun currentQueriesRecheckAfterEvaluationAndOversizedGraphsRefuseWithoutOmittingNodes(): Unit = runBlocking {
        val f = fixture("post-query-current", coroutineContext[Job]); var samples = 0
        val corpus = object : ProjectCorpus by f.corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                if (++samples == 2) f.corpus.put(project, id, "A changed source.".encodeToByteArray())
                return f.corpus.document(project, id)
            }
        }
        val wire = EnglishObservationWire(f.ctx, corpus)
        try {
            val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val refused = request(wire, cid, markerProgram("negation"), "current", 409)
            assertTrue((refused["error"] as String).contains("stale")); assertEquals(2, samples)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
        val large = fixture("large-observation-graph", coroutineContext[Job], copies = false, text = "A ".repeat(1100) + ".")
        val bounded = EnglishObservationWire(large.ctx, large.corpus)
        try {
            val cid = large.cas.put(CanonicalCbor.encodeMap(large.receipt))
            val refused = request(bounded, cid, markerProgram("negation"), status = 409)
            assertTrue((refused["error"] as String).contains("smaller text range"))
        } finally { bounded.drain(); large.owner.complete(); large.owner.join() }
    }

    @Test fun publishedPhaseOneReceiptRemainsHistoricalWithUnreportedCopyIdentity(): Unit = runBlocking {
        val f = fixture("published-phase-one", coroutineContext[Job], copies = false)
        val wire = EnglishObservationWire(f.ctx, f.corpus)
        try {
            val published = reifyStrict(Path.of("docs/evidence/2026-11-04/offline-english/browser-result.json").toFile().readText()) as Map<*, *>
            val original = (published["meta"] as Map<*, *>)["originalText"] as String
            // The published HTTP JSON export has only integer numeric fields; restore their CBOR
            // integer type rather than converting its JSON parser's doubles into floating CBOR.
            fun exported(value: Any?): Any? = when (value) {
                is Map<*, *> -> value.entries.associate { (it.key as String j exported(it.value)).pair }
                is List<*> -> value.map(::exported)
                is Number -> value.toDouble().let { number ->
                    require(number.isFinite() && number % 1.0 == 0.0); number.toLong()
                }
                else -> value
            }
            @Suppress("UNCHECKED_CAST")
            val inventory = exported(published["inventory"]) as Map<String, Any?>
            assertEquals(inventory["originalCid"], f.cas.put(original.encodeToByteArray()).value)
            val cid = f.cas.put(CanonicalCbor.encodeMap(inventory - "receiptCid"))
            assertEquals(inventory["receiptCid"], cid.value)
            val result = request(wire, cid, program(s_["?marker"], s_[Triple("?marker", term("markerKind"), "\"negation\"")]))
            assertEquals("legacy-unreported", (result["source"] as Map<*, *>)["dependencyNodeIdentity"])
            assertTrue(bindings(result).isNotEmpty())
            request(wire, cid, markerProgram("negation"), "current", 409)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }

    @Test fun explicitlyWiredNullInputsCannotFallBackToAuthoredSnapshotParameters(): Unit = runBlocking {
        val f = fixture("explicit-query-null", coroutineContext[Job]); val wire = EnglishObservationWire(f.ctx, f.corpus)
        try {
            wire.attach(); val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val node = LcncNode("query", EnglishObservationWire.TYPE, _m["receiptCid" j cid.value,
                "mode" j "snapshot", "program" j jsonOf(markerProgram("negation"))])
            f.corpus.put("legal", f.row.id, "A changed source.".encodeToByteArray())
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishObservationWire.TYPE])
            val authored = runner.run(node, emptyMap())
            assertEquals(true, (authored["source"] as Map<*, *>)["historical"])
            for (key in s_["mode", "receiptCid", "program"].view) {
                assertFailsWith<IllegalArgumentException>("Explicit null $key must not use an authored parameter") {
                    runner.run(node, _m[key j null])
                }
            }
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }
}
||||||| 5e8cff8ce
=======
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
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class EnglishObservationWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val owner: CompletableJob, val source: DocumentSource, val row: ProjectDoc, val receipt: Map<String, Any?>)

    /** Fixture annotations are recorded claims; this test does not claim actual parser execution. */
    private suspend fun fixture(name: String, parent: Job?, copies: Boolean = true, text: String = "Tenant may remain unless rent is not due."): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus(); val cid = cas.put(text.encodeToByteArray())
        corpus.put("legal", "agreement.txt", text.encodeToByteArray())
        val row = assertNotNull(corpus.document("legal", "agreement.txt")); val rev = DocumentRevision(row.project, row.id, row.cid, row.rev, row.seq)
        val source = DocumentSource(cid, cid, text, row.id, "text/plain", "fixture", category = "document", project = rev, extraction = rev)
        val matches = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text).toList()
        val tokens = matches.mapIndexed { i, word -> NlpToken(i + 1, word.range.first, word.range.last + 1,
            word.value, word.value.lowercase(), "NN", "O") }.toSeries()
        val edges = (0 until tokens.size).map { i -> NlpDependency(if (i == 0) 0 else 1, i + 1, if (i == 0) "root" else "dep") }.toMutableList()
        if (copies) { edges.add(NlpDependency(3, 3, "conj", 0, 1)); edges.add(NlpDependency(3, 4, "obj", 1, 0)) }
        val parsed = NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges.toSeries())], NlpMetadata("fixture", "EnglishObservationWireTest"))
        val receipt = EnglishInspection.inspect(source, parsed, 0, text.length) + _m["source" j (DocumentCuratorCodec.source(source) - "text"),
            "selection" j rev.toMap(), "sourceSnapshots" j _l[row.toMap()]]
        return Fixture(ctx, cas, corpus, owner, source, row, receipt)
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
        return reifyStrict(LcncProgramConfix.toJson(LcncProgram("English query", nodes.toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true)))) as Map<*, *>
    }
    private fun term(local: String) = "<${EnglishObservationGraph.VOCAB}$local>"
    private fun markerProgram(kind: String) = program(s_["?marker", "?quote"], s_[
        Triple("?marker", term("markerKind"), "\"$kind\""), Triple("?marker", term("quote"), "?quote")])
    private suspend fun request(wire: EnglishObservationWire, cid: ContentId, program: Map<*, *>, mode: String = "snapshot", status: Int = 200): Map<*, *> {
        val reply = wire.route("POST", EnglishObservationWire.PATH, jsonOf(_m["receiptCid" j cid.value, "mode" j mode, "program" j program]), null)
        assertEquals(status, reply.status, reply.body); return reifyStrict(reply.body) as Map<*, *>
    }
    private fun bindings(value: Map<*, *>) = ((value["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>

    @Test fun sourceSpannedNegationAndExceptionAreQueryableWithoutLegalTruthOrGlobalMutation(): Unit = runBlocking {
        val f = fixture("observations", coroutineContext[Job]); val wire = EnglishObservationWire(f.ctx, f.corpus)
        val global = QuadStore().add("<urn:unrelated>", "<urn:p>", "<urn:o>")
        try {
            wire.attach(); val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val result = request(wire, cid, markerProgram("negation"), "current")
            val row = bindings(result).single() as Map<*, *>
            fun value(key: String) = (row[key] as Map<*, *>)["value"] as String
            assertEquals("not", value("quote"))
            val marker = "<${value("marker")}>"
            val spans = bindings(request(wire, cid, program(s_["?begin", "?end"], s_[
                Triple(marker, term("begin"), "?begin"), Triple(marker, term("end"), "?end")]))).single() as Map<*, *>
            assertEquals("not", f.source.text.substring(((spans["begin"] as Map<*, *>)["value"] as String).toInt(),
                ((spans["end"] as Map<*, *>)["value"] as String).toInt()))
            val source = result["source"] as Map<*, *>; assertEquals("not-reverified", source["parserExecution"])
            assertEquals("not-established", source["producerAuthority"]); assertEquals("unreviewed", source["reviewStatus"])
            val exception = bindings(request(wire, cid, markerProgram("exception"))).single() as Map<*, *>
            assertEquals("unless", (exception["quote"] as Map<*, *>)["value"])
            val counts = program(s_["?node"], s_[Triple("?node", term("kind"), "\"token\""), Triple("?node", term("word"), "\"remain\"")])
            assertEquals(1, bindings(request(wire, cid, counts)).size, "Source occurrences must not count enhanced copies")
            val copies = program(s_["?node"], s_[Triple("?node", term("word"), "\"remain\"")])
            assertEquals(2, bindings(request(wire, cid, copies)).size)
            val roles = program(s_["?edge"], s_[Triple("?edge", term("relation"), "\"obj\""),
                Triple("?edge", term("governor"), "<urn:trikeshed:english:${cid.hex}/sentence/0/node/3:1>")])
            assertEquals(1, bindings(request(wire, cid, roles)).size)
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishObservationWire.TYPE])
            val viaNode = runner.run(LcncNode("query", EnglishObservationWire.TYPE), _m["receiptCid" j cid.value,
                "mode" j "current", "program" j markerProgram("negation")])
            assertEquals("english-receipt-observations", viaNode["dataset"])
            assertEquals(1, global.size); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            f.corpus.put("legal", f.row.id, "Changed source.".encodeToByteArray())
            request(wire, cid, markerProgram("negation"), "current", 409)
            assertEquals(1, bindings(request(wire, cid, markerProgram("negation"), "snapshot")).size)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
        assertNull(f.ctx.routes.match(EnglishObservationWire.PATH)); assertNull(f.ctx.lcncRunners[EnglishObservationWire.TYPE])
    }

    @Test fun malformedClaimsInjectionAndLegacyCurrentnessRefuse(): Unit = runBlocking {
        val f = fixture("invalid-observations", coroutineContext[Job], copies = false); val wire = EnglishObservationWire(f.ctx, f.corpus)
        try {
            val changed = f.receipt + _m["originalCid" j ContentId.of("forged".encodeToByteArray()).value]
            val bad = f.cas.put(CanonicalCbor.encodeMap(changed)); request(wire, bad, markerProgram("negation"), status = 409)
            val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val injection = program(s_["?x"], s_[Triple("?x", term("kind") + " . ?x ?p ?o", "\"marker\"")])
            request(wire, cid, injection, status = 400)
            val legacy = (f.receipt - "sourceSnapshots") + _m["counts" j ((f.receipt["counts"] as Map<*, *>) - "nodes"),
                "coverage" j ((f.receipt["coverage"] as Map<*, *>) - _s["dependencyNodeIdentity", "parserDependencyNodeIdentity", "parserNodeIdentityReported"]),
                "sentences" j (f.receipt["sentences"] as List<*>).map { raw -> val row = raw as Map<*, *>
                    (row - "nodes") + _m["dependencies" j (row["dependencies"] as List<*>).map { edge ->
                        (edge as Map<*, *>) - _s["governorCopy", "dependentCopy", "governorNode", "dependentNode"]
                    }]
                }]
            val old = f.cas.put(CanonicalCbor.encodeMap(legacy))
            request(wire, old, markerProgram("negation"), "current", 409)
            val historical = request(wire, old, markerProgram("negation"))
            assertEquals("legacy-unreported", (historical["source"] as Map<*, *>)["dependencyNodeIdentity"])
            assertEquals(405, wire.route("DELETE", EnglishObservationWire.PATH, "", null).status)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }

    @Test fun literalControlsRoundTripAndWrongBytesCannotImpersonateAReceipt(): Unit = runBlocking {
        val f = fixture("literal-observations", coroutineContext[Job], copies = false, text = "Tenant\tmay remain\u000Cunless rent is not due.")
        try {
            val bytes = CanonicalCbor.encodeMap(f.receipt); val cid = f.cas.put(bytes)
            val graph = EnglishObservationGraph.project(EnglishObservationGraph.read(f.cas, cid)).toGraph()
            assertTrue(graph.quads.any { (it.o as? RdfTerm.Literal)?.lexical == f.source.text })
            val bad = object : CasStore(), BoundedCasReader {
                override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray = "different bytes".encodeToByteArray()
            }
            assertFailsWith<IllegalArgumentException> { EnglishObservationGraph.read(bad, cid) }
        } finally { f.owner.complete(); f.owner.join() }
    }

    @Test fun currentQueriesRecheckAfterEvaluationAndOversizedGraphsRefuseWithoutOmittingNodes(): Unit = runBlocking {
        val f = fixture("post-query-current", coroutineContext[Job]); var samples = 0
        val corpus = object : ProjectCorpus by f.corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                if (++samples == 2) f.corpus.put(project, id, "A changed source.".encodeToByteArray())
                return f.corpus.document(project, id)
            }
        }
        val wire = EnglishObservationWire(f.ctx, corpus)
        try {
            val cid = f.cas.put(CanonicalCbor.encodeMap(f.receipt))
            val refused = request(wire, cid, markerProgram("negation"), "current", 409)
            assertTrue((refused["error"] as String).contains("stale")); assertEquals(2, samples)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
        val large = fixture("large-observation-graph", coroutineContext[Job], copies = false, text = "A ".repeat(1100) + ".")
        val bounded = EnglishObservationWire(large.ctx, large.corpus)
        try {
            val cid = large.cas.put(CanonicalCbor.encodeMap(large.receipt))
            val refused = request(bounded, cid, markerProgram("negation"), status = 409)
            assertTrue((refused["error"] as String).contains("smaller text range"))
        } finally { bounded.drain(); large.owner.complete(); large.owner.join() }
    }

    @Test fun publishedPhaseOneReceiptRemainsHistoricalWithUnreportedCopyIdentity(): Unit = runBlocking {
        val f = fixture("published-phase-one", coroutineContext[Job], copies = false)
        val wire = EnglishObservationWire(f.ctx, f.corpus)
        try {
            val published = reifyStrict(Path.of("docs/evidence/2026-11-04/offline-english/browser-result.json").toFile().readText()) as Map<*, *>
            val original = (published["meta"] as Map<*, *>)["originalText"] as String
            // The published HTTP JSON export has only integer numeric fields; restore their CBOR
            // integer type rather than converting its JSON parser's doubles into floating CBOR.
            fun exported(value: Any?): Any? = when (value) {
                is Map<*, *> -> value.entries.associate { (it.key as String j exported(it.value)).pair }
                is List<*> -> value.map(::exported)
                is Number -> value.toDouble().let { number ->
                    require(number.isFinite() && number % 1.0 == 0.0); number.toLong()
                }
                else -> value
            }
            @Suppress("UNCHECKED_CAST")
            val inventory = exported(published["inventory"]) as Map<String, Any?>
            assertEquals(inventory["originalCid"], f.cas.put(original.encodeToByteArray()).value)
            val cid = f.cas.put(CanonicalCbor.encodeMap(inventory - "receiptCid"))
            assertEquals(inventory["receiptCid"], cid.value)
            val result = request(wire, cid, program(s_["?marker"], s_[Triple("?marker", term("markerKind"), "\"negation\"")]))
            assertEquals("legacy-unreported", (result["source"] as Map<*, *>)["dependencyNodeIdentity"])
            assertTrue(bindings(result).isNotEmpty())
            request(wire, cid, markerProgram("negation"), "current", 409)
        } finally { wire.drain(); f.owner.complete(); f.owner.join() }
    }
}
>>>>>>> 273a6f1f87d0e77c2297f9f9a7197bf986bf5edf
