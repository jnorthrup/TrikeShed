package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Genuine saved CoreNLP4.6 syntax-v1 observations are replayed; these tests never execute a parser. */
class EnglishNalPresetExecutionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val s12 = ContentId("sha256:b8d43e44fd90ed082538dee097f2eaad7f2eddc718baf5148afb5762da9e53f0")
    private val guard = ContentId("sha256:9ee6a4c328fe5bff1dd898019e51ff862032992985f4a3c3270c100f4e4e27c2")
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/english-nal/$name")).use { it.readBytes() }
    private fun recorded(name: String) = Base64.getMimeDecoder().decode(resource("$name-inspection.cbor.b64"))
    @Suppress("UNCHECKED_CAST") private fun fields(value: Any?) = value as Map<String, Any?>

    private class Fixture(val ctx: ModuleContext, val owner: CompletableJob, val wire: EnglishObservationWire,
        val board: BoardStoreElement, val rows: MutableMap<String, ProjectDoc>, val reads: AtomicInteger,
        val mutateAtRead: AtomicInteger, val extractionCalls: AtomicInteger, val cas: FileCasStore, val casPath: String,
        val snapshot: EnglishObservationGraph.Snapshot) {
        fun registry() = PureNodes.registry { 123L } + ctx.lcncRunners + LcncKanbanExperience(board).registry() +
            TribunalNodes.registry(TribunalDialog { _, _, _ -> error("Retained NAL cannot call a model") }).filterKeys { it == "display" }
    }

    private suspend fun fixture(name: String, parent: Job?, input: String = "s12"): Fixture {
        val files = JvmFileOperations(); val casPath = directory.resolve(name).toString(); val cas = FileCasStore(files, casPath)
        val receiptCid = if (input == "s12") s12 else guard
        assertEquals(receiptCid, cas.put(recorded(input)))
        val bytes = resource("$input-source.txt"); cas.put(bytes)
        val snapshot = EnglishObservationGraph.read(cas, receiptCid)
        assertEquals(bytes.decodeToString(), snapshot.source.text)
        assertEquals("4.6.0", snapshot.parsed.metadata?.runtime?.get("packageVersion"))
        val rows = ConcurrentHashMap<String, ProjectDoc>()
        for (row in assertNotNull(snapshot.sourceSnapshots)) rows[row.project + "\u0000" + row.id] = row
        val reads = AtomicInteger(); val mutateAtRead = AtomicInteger(-1); val extractionCalls = AtomicInteger()
        val corpus = object : ProjectCorpus by InMemoryProjectCorpus() {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                if (reads.incrementAndGet() == mutateAtRead.get()) rows.clear()
                return rows[project + "\u0000" + id]
            }
            override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? {
                extractionCalls.incrementAndGet(); error("Retained NAL cannot extract or parse")
            }
        }
        val owner = SupervisorJob(parent); val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(),
            CoroutineScope(Dispatchers.IO + owner + files), { 123L }, directory.toFile())
        val board = BoardStoreElement(null, cas, parentJob = owner); board.open()
        val wire = EnglishObservationWire(ctx, corpus); wire.attach()
        return Fixture(ctx, owner, wire, board, rows, reads, mutateAtRead, extractionCalls, cas, casPath, snapshot)
    }

    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        f.wire.drain(); f.board.drain(); f.owner.complete(); f.owner.join()
        assertTrue(f.owner.isCompleted && f.board.supervisor.isCompleted)
    }
    private suspend fun evaluate(f: Fixture, mode: String = "snapshot") =
        LcncRunner(f.registry()).runProcedure(EnglishNalPreset.build(f.snapshot.receiptCid.value, mode))
    private suspend fun untouched(f: Fixture) {
        assertEquals(0, f.extractionCalls.get()); assertEquals(0, f.ctx.rete.snapshot().size)
        assertTrue(f.ctx.blackboard.snapshot().values.isEmpty()); assertNull(f.ctx.beliefBag); assertNull(f.ctx.kifBank)
        assertNull(f.ctx.documentReview); assertNull(f.ctx.routes.match("/api/documents/english/nal"))
    }

    @Test fun actualRegisteredPresetKeepsExactReportAndEverySameSourceOccurrence(): Unit = runBlocking {
        val f = fixture("nal-registered", coroutineContext[Job])
        try {
            val result = evaluate(f, "current"); val output = result.nodeOutputs.getValue("evaluate")
            val expected = SyntheticNalExperiment.run(f.snapshot).toMap()
            assertEquals(expected, output["report"]); assertEquals(expected, result.returns["report"])
            assertEquals("EVALUATED", output["status"]); assertEquals("historical-snapshot", fields(output["report"])["mode"])
            assertEquals("not-checked", fields(output["report"])["currentness"])
            assertEquals("current", fields(output["source"])["mode"])
            assertEquals("checked-before-and-after-evaluation", fields(output["source"])["currentness"])
            assertEquals("not-performed", fields(output["source"])["admission"])
            val passages = (output["passages"] as List<*>).map(::fields)
            assertEquals(5, passages.size); assertEquals(4, (expected["premises"] as List<*>).size)
            assertEquals(_l[0, 20, 40, 63, 86], passages.map { (it["begin"] as Number).toInt() })
            assertEquals(5, passages.map { it["occurrenceCid"] }.toSet().size)
            assertEquals(4, passages.map { it["contributionCid"] }.toSet().size)
            for (passage in passages) {
                val begin = (passage["begin"] as Number).toInt(); val end = (passage["end"] as Number).toInt()
                val quotation = f.snapshot.source.quotation(begin, end)
                for ((key, value) in quotation) assertEquals(value, passage[key])
                assertEquals(s12.value, passage["inspectionCid"])
                assertEquals(output["frameCid"], passage["frameCid"])
            }
            val selected = result.nodeOutputs.getValue("passage")
            assertEquals(true, selected["found"])
            assertEquals(reifyStrict(jsonOf(passages.first())), selected["value"])
            assertEquals("kotlin", selected["geometry"])
            assertEquals(ContentId.of(jsonOf(passages).encodeToByteArray()).value, selected["sourceCid"], "Path geometry identifies serialized JSON, not the English text")
            assertNotEquals(f.snapshot.source.extractedTextCid.value, selected["sourceCid"])
            assertNull(f.cas.get(ContentId(output["frameCid"] as String)), "Computed frame identity is not a retained report block")
            untouched(f)
        } finally { close(f) }
        assertNull(f.ctx.lcncRunners[EnglishNalContract.TYPE])
    }

    @Test fun currentMutationRefusesButExplicitHistoricalModeKeepsTheExactReport(): Unit = runBlocking {
        val f = fixture("nal-current", coroutineContext[Job])
        try {
            val registry = f.registry()
            val program = EnglishNalPreset.build(s12.value, "current")
            val walker = LcncRunner(registry)
            val current = walker.runProcedure(program).nodeOutputs.getValue("evaluate")
            val readsAfterFirstRun = f.reads.get()
            assertTrue(readsAfterFirstRun > 0)
            f.rows.clear()
            assertFailsWith<IllegalArgumentException> { walker.runProcedure(program) }
            assertTrue(f.reads.get() > readsAfterFirstRun, "A fresh run of the same program/receipt must recheck source rows rather than replay cached current success")
            val historical = LcncRunner(registry).runProcedure(EnglishNalPreset.build(s12.value, "snapshot")).nodeOutputs.getValue("evaluate")
            assertEquals(current["report"], historical["report"])
            assertEquals("snapshot", fields(historical["source"])["mode"])
            assertEquals("not-checked", fields(historical["source"])["currentness"])
            untouched(f)
        } finally { close(f) }
    }

    @Test fun postEvaluationCurrentnessCheckRejectsAChangedSource(): Unit = runBlocking {
        val f = fixture("nal-final-current", coroutineContext[Job])
        try {
            f.mutateAtRead.set(f.reads.get() + 2)
            assertFailsWith<IllegalArgumentException> { evaluate(f, "current") }
            assertEquals(2, f.reads.get(), "A source change at the final check cannot return a report")
            val historical = evaluate(f).nodeOutputs.getValue("evaluate")
            assertEquals("EVALUATED", historical["status"])
            untouched(f)
        } finally { close(f) }
    }

    @Test fun unsupportedRecordedEnglishIsAWholeFrameStatusWithoutFallback(): Unit = runBlocking {
        val f = fixture("nal-unsupported", coroutineContext[Job], "guard")
        try {
            val output = evaluate(f).nodeOutputs.getValue("evaluate")
            assertEquals(SyntheticNalExperiment.run(f.snapshot).toMap(), output["report"])
            assertEquals("UNSUPPORTED", output["status"])
            val report = fields(output["report"])
            assertEquals(emptyList<Any?>(), report["premises"]); assertEquals(emptyList<Any?>(), report["derivations"])
            assertEquals(emptyList<Any?>(), output["passages"])
            assertTrue((report["issues"] as List<*>).isNotEmpty())
            untouched(f)
        } finally { close(f) }
    }

    @Test fun unknownNullAndMissingInputsRefuseAndDrainRemovesOnlyItsOwnRegistration(): Unit = runBlocking {
        val f = fixture("nal-inputs", coroutineContext[Job])
        val runner = assertNotNull(f.ctx.lcncRunners[EnglishNalContract.TYPE])
        val node = LcncNode("evaluate", EnglishNalContract.TYPE, _m["receiptCid" j s12.value, "mode" j "snapshot"])
        try {
            for (inputs in s_[
                _m["receiptCid" j null], _m["mode" j null], _m["mode" j ""],
                _m["mode" j "union"], _m["text" j "ALPHA implies BETA."],
                _m["cas" j "/caller/store"], _m["report" j emptyMap<String, Any?>()],
                _m["reviewer" j "caller"], _m["confidence" j .99], _m["admit" j true],
            ].view) assertFailsWith<IllegalArgumentException> { runner.run(node, inputs) }
            assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("empty", EnglishNalContract.TYPE), emptyMap()) }
            assertFailsWith<IllegalArgumentException> { runner.run(node.copy(params = node.params + _m["dataset" j "caller"]), emptyMap()) }
            assertFailsWith<IllegalArgumentException> {
                runner.run(node, _m["receiptCid" j ("sha256:" + "f".repeat(64))])
            }
            val forged = CanonicalCbor.decodeMap(recorded("s12"))
            val sentences = (forged["sentences"] as List<*>).toMutableList()
            val first = fields(sentences[0]); val tokens = (first["tokens"] as List<*>).toMutableList()
            tokens[0] = fields(tokens[0]) + _m["word" j "OMEGA"]
            sentences[0] = first + _m["tokens" j tokens]
            val forgedCid = f.cas.put(CanonicalCbor.encodeMap(forged + _m["sentences" j sentences]))
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["receiptCid" j forgedCid.value]) }
            // The ordinary walker applies readiness before dispatch: neither null nor
            // missing required wires invoke this runner, even with authored defaults.
            val dispatches = AtomicInteger()
            val observed = f.registry() + _m[EnglishNalContract.TYPE j LcncNodeRunner { selected, inputs ->
                dispatches.incrementAndGet()
                runner.execute(selected, inputs)
            }]
            val readsBefore = f.reads.get()
            for (port in s_["receiptCid", "mode"].view) for (explicitNull in s_[false, true].view) {
                val feeder = LcncNode("unfed", LcncContracts.SCOPE_IN,
                    _m["name" j "missing", "kind" j if (port == "receiptCid") "id" else "text"])
                val program = LcncProgram("nal-required-wire-readiness", s_[
                    LcncNode("receipt", LcncContracts.SCOPE_IN, _m["name" j "receiptCid", "kind" j "id", "default" j s12.value]),
                    LcncNode("mode", "text.value", _m["value" j "snapshot"]), feeder,
                    node, LcncNode("out", LcncContracts.SCOPE_OUT, _m["name" j "report", "kind" j "json"]),
                ], s_[
                    LcncWire(if (port == "receiptCid") "unfed" else "receipt", "value", "evaluate", "receiptCid"),
                    LcncWire(if (port == "mode") "unfed" else "mode", "value", "evaluate", "mode"),
                    LcncWire("evaluate", "report", "out", "value"),
                ])
                assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
                val invocations = LcncInvocationLedger()
                val walker = LcncRunner(observed); walker.invocations = invocations
                val arguments: Map<String, Any?> = if (explicitNull) _m["missing" j null] else emptyMap()
                val walked = walker.runProcedure(program, arguments)
                if (explicitNull) {
                    assertTrue(walked.nodeOutputs.getValue("unfed").containsKey("value"))
                    assertNull(walked.nodeOutputs.getValue("unfed")["value"])
                } else assertFalse(walked.nodeOutputs.containsKey("unfed"))
                assertFalse(walked.nodeOutputs.containsKey("evaluate"), "$port / null=$explicitNull must not produce a report")
                assertTrue(walked.returns.isEmpty())
                val entry = (invocations.snapshot()["entries"] as List<*>).map(::fields).single { it["nodeId"] == "evaluate" }
                assertEquals("skipped", entry["status"]); assertEquals("unfed", entry["reason"])
                assertEquals(0, dispatches.get(), "Required-wire readiness must prevent dispatch, not replace it with a synthetic or unsupported report")
                assertEquals(readsBefore, f.reads.get())
                untouched(f)
            }
            val replacement = LcncNodeRunner { _, _ -> emptyMap<String, Any?>() }
            f.ctx.lcncRunners[EnglishNalContract.TYPE] = replacement
            f.wire.drain()
            assertSame(replacement, f.ctx.lcncRunners[EnglishNalContract.TYPE])
            assertFailsWith<ClosedSendChannelException> { runner.run(node, emptyMap()) }
            untouched(f)
        } finally { close(f) }
    }

    @Test fun productionLargeOutputReferencesPreserveTheFullReportAcrossFileStoreReopen(): Unit = runBlocking {
        val f = fixture("nal-output-retention", coroutineContext[Job]); var closed = false
        try {
            val base = EnglishNalPreset.build(s12.value)
            val extraNodes = (0 until 8).toSeries() α { i -> LcncNode("copy-$i", "display") }
            val extraWires = (0 until 8).toSeries() α { i -> LcncWire("evaluate", "report", "copy-$i", "x") }
            val program = base.copy(nodes = (base.nodes.size + extraNodes.size) j { i: Int ->
                if (i < base.nodes.size) base.nodes[i] else extraNodes[i - base.nodes.size] },
                wires = (base.wires.size + extraWires.size) j { i: Int ->
                    if (i < base.wires.size) base.wires[i] else extraWires[i - base.wires.size] })
            val result = LcncRunner(f.registry()).runProcedure(program)
            val raw = _m["outputs" j result.nodeOutputs, "returns" j result.returns]
            assertTrue(jsonOf(raw).length > 131_072, "Actual report fanout exceeds the default character budget independent of timing")
            assertNotNull(ValueBudget().violation(raw), "Presentation fanout makes a real completed output exceed the ordinary response budget")
            val projection = LcncOutputReceipt.retain(f.cas, raw)
            assertEquals(true, projection["outputPreview"])
            val outputCid = ContentId(projection["outputCid"] as String)
            val nodeRef = fields(fields(projection["outputs"])["evaluate"])
            assertEquals(true, nodeRef["previewOnly"]); assertEquals("application/json", nodeRef["contentType"])
            val nodeCid = ContentId(nodeRef["retainedCid"] as String)
            assertNotEquals(result.nodeOutputs.getValue("evaluate")["frameCid"], nodeCid.value)
            f.cas.sync(); close(f); closed = true
            val reopened = FileCasStore(JvmFileOperations(), f.casPath)
            val rawBytes = assertNotNull(reopened.getBounded(outputCid, 4 * 1024 * 1024))
            assertContentEquals(jsonOf(raw).encodeToByteArray(), rawBytes); assertEquals(outputCid, ContentId.of(rawBytes))
            val nodeBytes = assertNotNull(reopened.getBounded(nodeCid, 4 * 1024 * 1024))
            assertEquals(nodeCid, ContentId.of(nodeBytes))
            val retained = fields(reifyStrict(nodeBytes.decodeToString()))
            assertEquals(reifyStrict(jsonOf(SyntheticNalExperiment.run(f.snapshot).toMap())), retained["report"])
            assertEquals(5, (retained["passages"] as List<*>).size, "A preview cannot replace the full retained premise occurrence inventory")
        } finally { if (!closed) close(f) }
    }
}
