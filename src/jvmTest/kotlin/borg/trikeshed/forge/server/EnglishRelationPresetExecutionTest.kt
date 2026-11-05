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
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Executes the offered procedure against retained fixture observations; no parser execution claim. */
class EnglishRelationPresetExecutionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private class Fixture(val ctx: ModuleContext, val owner: CompletableJob, val wire: EnglishObservationWire,
        val board: BoardStoreElement, val snapshot: EnglishObservationGraph.Snapshot,
        val reads: AtomicInteger, val mutateAt: AtomicInteger) {
        fun registry() = PureNodes.registry { 123L } + ctx.lcncRunners + LcncKanbanExperience(board).registry() +
            TribunalNodes.registry(TribunalDialog { _, _, _ -> error("Relations cannot call a model") }).filterKeys { it == "display" }
    }
    private suspend fun fixture(name: String, parent: Job?): Fixture {
        val input = relationSnapshot("Alice pays Bob.\nBob owes Alice.")
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        cas.put(input.source.text.encodeToByteArray())
        val revision = input.selection
        val row = ProjectDoc(revision.project, revision.id, revision.cid, revision.rev, revision.seq,
            input.source.text.encodeToByteArray().size.toLong(), "text/plain")
        val receipt = input.retainedReceipt + _m["sourceSnapshots" j _l[row.toMap()]]
        val snapshot = EnglishObservationGraph.read(cas, cas.put(CanonicalCbor.encodeMap(receipt)))
        val reads = AtomicInteger(); val mutateAt = AtomicInteger(-1)
        val corpus = object : ProjectCorpus by InMemoryProjectCorpus() {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                if (reads.incrementAndGet() >= mutateAt.get() && mutateAt.get() >= 0) return null
                return row.takeIf { it.project == project && it.id == id }
            }
            override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? = error("Relations cannot extract or parse")
        }
        val owner = SupervisorJob(parent); val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(),
            CoroutineScope(Dispatchers.IO + owner + files), { 123L }, directory.toFile())
        val board = BoardStoreElement(null, cas, parentJob = owner); board.open()
        val wire = EnglishObservationWire(ctx, corpus); wire.attach()
        return Fixture(ctx, owner, wire, board, snapshot, reads, mutateAt)
    }
    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        f.wire.drain(); f.board.drain(); f.owner.complete(); f.owner.join()
        assertNull(f.ctx.lcncRunners[EnglishRelationContract.TYPE])
    }
    private suspend fun evaluate(f: Fixture, mode: String = "snapshot", query: String = "{}") =
        LcncRunner(f.registry()).runProcedure(EnglishRelationPreset.build(f.snapshot.receiptCid.value, mode, query))
    private suspend fun untouched(f: Fixture) {
        assertEquals(0, f.ctx.rete.snapshot().size); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        assertNull(f.ctx.beliefBag); assertNull(f.ctx.kifBank); assertNull(f.ctx.documentReview)
    }

    @Test fun catalogPresetExecutesAgainstRetainedQuads(): Unit = runBlocking {
        val f = fixture("relations-catalog", coroutineContext[Job])
        try {
            assertTrue(LcncPresets.catalog().any { it.name == EnglishRelationPreset.NAME })
            val offered = LcncProgramConfix.fromJson(EnglishRelationPreset.NAME, LcncPresets.all().getValue(EnglishRelationPreset.NAME))
            assertTrue(LcncTypeCheck.check(offered, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
            val result = LcncRunner(f.registry()).runProcedure(offered, _m["receiptCid" j f.snapshot.receiptCid.value])
            val output = result.nodeOutputs.getValue("relations")
            assertEquals("OBSERVED", output["status"])
            val report = output["report"] as Map<*, *>
            assertTrue((report["quads"] as Number).toInt() > 0)
            val bindings = ((report["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>
            assertEquals(2, bindings.size); assertEquals(2, (result.returns["matches"] as List<*>).size)
            assertTrue((report["query"] as String).contains(f.snapshot.graph))
            val filtered = evaluate(f, query = "{\"predicate\":\"pay\",\"subject\":\"Alice\",\"object\":\"Bob\"}")
            assertEquals(1, (filtered.returns["matches"] as List<*>).size)
            val reverse = evaluate(f, query = "{\"predicate\":\"pay\",\"subject\":\"Bob\",\"object\":\"Alice\"}")
            assertTrue((reverse.returns["matches"] as List<*>).isEmpty())
            assertEquals(false, reverse.nodeOutputs.getValue("passage")["found"])
            untouched(f)
        } finally { close(f) }
    }
    @Test fun selectedPassagePreservesSource(): Unit = runBlocking {
        val f = fixture("relations-source", coroutineContext[Job])
        try {
            val result = evaluate(f, "current", "{\"predicate\":\"owe\"}")
            val passage = (result.nodeOutputs.getValue("relations")["passages"] as List<*>).single() as Map<*, *>
            assertEquals("Bob owes Alice.", passage["quote"])
            assertEquals(16, passage["begin"]); assertEquals(f.snapshot.receiptCid.value, passage["inspectionCid"])
            assertEquals(reifyStrict(jsonOf(passage)), result.nodeOutputs.getValue("passage")["value"])
            assertEquals("checked-before-and-after-query", (result.returns["source"] as Map<*, *>)["currentness"])
            val expected = f.snapshot.source.quotation(16, f.snapshot.source.text.length)
            for ((key, value) in expected) assertEquals(value, passage[key])
            untouched(f)
        } finally { close(f) }
    }
    @Test fun unknownReceiptRefused(): Unit = runBlocking {
        val f = fixture("relations-refusal", coroutineContext[Job])
        try {
            val runner = assertNotNull(f.ctx.lcncRunners[EnglishRelationContract.TYPE])
            val node = LcncNode("relations", EnglishRelationContract.TYPE, _m["receiptCid" j f.snapshot.receiptCid.value, "mode" j "snapshot"])
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["receiptCid" j ("sha256:" + "f".repeat(64))]) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["query" j null]) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["admit" j true]) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["mode" j null]) }
            val omitted = runner.run(node, emptyMap())
            assertEquals(2, (omitted["matches"] as List<*>).size)
            untouched(f)
        } finally { close(f) }
    }
    @Test fun currentSourceChangeRefused(): Unit = runBlocking {
        val f = fixture("relations-currentness", coroutineContext[Job])
        try {
            f.mutateAt.set(2)
            assertFailsWith<IllegalArgumentException> { evaluate(f, "current") }
            assertEquals(2, f.reads.get(), "Final currentness check must refuse after the actual quad query")
            val historical = evaluate(f)
            assertEquals(2, (historical.returns["matches"] as List<*>).size)
            assertEquals("not-checked", (historical.returns["source"] as Map<*, *>)["currentness"])
            untouched(f)
        } finally { close(f) }
    }
}
