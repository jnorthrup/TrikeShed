package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.forge.server.EnglishObservationWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.EnglishObservationGraph
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual saved CoreNLP4.6 annotations, replayed through the real query owner; no parser or model. */
class EnglishCopularPresetExecutionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val receipt = ContentId("sha256:311ce8ad376ec995c7fa253ec2736fe98e2d5e77980f236040fffcefad8f6c6c")
    private val source = ContentId("sha256:1f310bfb50270455e6aa301123450c519227a30bef7aec0aa58406c9c4a52ae5")
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/english-copular/$name")).use { it.readBytes() }
    @Suppress("UNCHECKED_CAST") private fun fields(value: Any?) = value as Map<String, Any?>
    private fun rows(value: Map<*, *>) = fields(fields(value["results"])["results"])["bindings"] as List<*>
    private fun cell(value: Any?, name: String) = fields(fields(value)[name])["value"] as String

    private class Fixture(val ctx: ModuleContext, val owner: CompletableJob, val wire: EnglishObservationWire,
        val sourceRows: MutableMap<String, ProjectDoc>, val extractionCalls: AtomicInteger,
        val snapshot: EnglishObservationGraph.Snapshot)

    private fun fixture(name: String, parent: Job?): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        assertEquals(receipt, cas.put(resource("inspection.cbor")))
        assertEquals(source, cas.put(resource("source.txt")))
        val snapshot = EnglishObservationGraph.read(cas, receipt)
        val sourceRows = ConcurrentHashMap<String, ProjectDoc>()
        for (row in assertNotNull(snapshot.sourceSnapshots)) sourceRows[row.project + "\u0000" + row.id] = row
        val extractionCalls = AtomicInteger()
        val corpus = object : ProjectCorpus by InMemoryProjectCorpus() {
            override suspend fun document(project: String, id: String) = sourceRows[project + "\u0000" + id]
            override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText? {
                extractionCalls.incrementAndGet(); error("Recorded inspection queries cannot extract or parse documents")
            }
        }
        val owner = SupervisorJob(parent); val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(),
            CoroutineScope(Dispatchers.IO + owner + files), { 123L }, directory.toFile())
        val wire = EnglishObservationWire(ctx, corpus); wire.attach()
        return Fixture(ctx, owner, wire, sourceRows, extractionCalls, snapshot)
    }

    private suspend fun close(f: Fixture) = withContext(NonCancellable) {
        f.wire.drain(); f.owner.complete(); f.owner.join(); assertTrue(f.owner.isCompleted)
    }

    private suspend fun query(f: Fixture, mode: String = "snapshot", cid: ContentId = receipt,
        program: LcncProgram = EnglishLcncPreset.copularPattern(), status: Int = 200): Map<String, Any?> {
        val route = assertNotNull(f.ctx.routes.match(EnglishObservationWire.PATH))
        val reply = assertNotNull(route.route("POST", EnglishObservationWire.PATH,
            jsonOf(_m["receiptCid" j cid.value, "mode" j mode, "program" j reifyStrict(LcncProgramConfix.toJson(program))]), null))
        assertEquals(status, reply.status, reply.body)
        return reifyMap(reply.body)
    }

    @Test fun actualCopiedAndNegatedCopularReadingsRemainSourceLinkedSyntax(): Unit = runBlocking {
        val f = fixture("copular-recorded", coroutineContext[Job])
        try {
            assertEquals("4.6.0", fields(fields(f.snapshot.inventory["parser"])["runtime"])["packageVersion"])
            val registry = PureNodes.registry { 123L } + f.ctx.lcncRunners +
                TribunalNodes.registry(TribunalDialog { _, _, _ -> error("Copular syntax query cannot call a model") }).filterKeys { it == "display" }
            val executed = LcncRunner(registry).runProcedure(EnglishLcncPreset.buildCopular(receipt.value))
            val result = executed.nodeOutputs.getValue("query"); val bindings = rows(result)
            assertEquals(3, bindings.size)
            val copies = bindings.filter { cell(it, "subjectWord") == "requirements" }
            assertEquals(2, copies.size)
            assertEquals(_s["2:0", "2:3"], copies.map { cell(it, "subject").substringAfterLast('/') }.toSet())
            assertTrue(copies.all { cell(it, "predicateWord") == "clear" })
            assertEquals(1, (f.snapshot.inventory["sentences"] as List<*>).flatMap { fields(it)["tokens"] as List<*> }
                .count { fields(it)["word"] == "requirements" }, "Two parser vertices are not two source occurrences")
            val negated = bindings.single { cell(it, "subjectWord") == "rent" }
            assertEquals("due", cell(negated, "predicateWord"))
            assertTrue(cell(negated, "head").endsWith("/sentence/2/node/8:0"))
            val sentence = fields((f.snapshot.inventory["sentences"] as List<*>)[2])
            assertEquals("Tenant may occupy unless rent is not due, and only if the permit remains valid.", sentence["quote"])
            assertTrue((sentence["markers"] as List<*>).any { fields(it)["quote"] == "not" })
            assertTrue(bindings.all { !(it as Map<*, *>).containsKey("quote") }, "The bounded word result must not pretend to include full source context")
            val descriptor = fields(result["source"])
            assertEquals(receipt.value, descriptor["receiptCid"]); assertEquals(f.snapshot.graph, descriptor["namedGraph"])
            assertEquals("unreviewed", descriptor["reviewStatus"]); assertEquals("not-established", descriptor["semanticAuthority"])
            assertEquals("not-reverified", descriptor["parserExecution"])
            assertEquals(result["results"], executed.nodeOutputs.getValue("bindings")["x"])
            assertEquals(result["source"], executed.nodeOutputs.getValue("source")["x"])
            assertEquals(0, f.ctx.rete.snapshot().size); assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
            assertNull(f.ctx.beliefBag); assertNull(f.ctx.documentReview); assertNull(f.ctx.routes.match("/api/documents/english"))
            assertEquals(0, f.extractionCalls.get())
        } finally { close(f) }
    }

    @Test fun currentSourceChangeRefusesWhileExplicitSnapshotKeepsRecordedQualifications(): Unit = runBlocking {
        val f = fixture("copular-currentness", coroutineContext[Job])
        try {
            val current = query(f, mode = "current")
            assertEquals("checked-before-and-after-query", fields(current["source"])["currentness"])
            f.sourceRows.clear()
            val failure = query(f, mode = "current", status = 409)
            assertFalse(failure.containsKey("results")); assertFalse(failure.containsKey("source"))
            val historical = query(f)
            assertEquals(rows(current), rows(historical))
            assertEquals(true, fields(historical["source"])["historical"])
            assertEquals("not-checked", fields(historical["source"])["currentness"])
            assertEquals(0, f.extractionCalls.get())
        } finally { close(f) }
    }

    @Test fun forgedSourceWordsAndNinthPatternRefuseWithoutPartialResults(): Unit = runBlocking {
        val f = fixture("copular-refusal", coroutineContext[Job])
        try {
            val inventory = CanonicalCbor.decodeMap(resource("inspection.cbor"))
            val sentences = (inventory["sentences"] as List<*>).toMutableList()
            val sentence = fields(sentences[1]); val tokens = (sentence["tokens"] as List<*>).toMutableList()
            tokens[1] = fields(tokens[1]) + _m["word" j "invented"]
            sentences[1] = sentence + _m["tokens" j tokens]
            val forged = f.ctx.casStore.put(CanonicalCbor.encodeMap(inventory + _m["sentences" j sentences]))
            val invalid = query(f, cid = forged, status = 409)
            assertFalse(invalid.containsKey("results")); assertFalse(invalid.containsKey("source"))
            val program = EnglishLcncPreset.copularPattern()
            val extra = LcncNode("over-budget", SparqlGraph.PATTERN, _m["subject" j "?head",
                "predicate" j "<urn:trikeshed:english:observation:word>", "object" j "?extra"])
            val ninth = program.copy(nodes = (program.nodes.size + 1) j { i: Int -> if (i < program.nodes.size) program.nodes[i] else extra },
                wires = (program.wires.size + 1) j { i: Int -> if (i < program.wires.size) program.wires[i]
                    else LcncWire(extra.id, "pattern", "select", "patterns") })
            val over = query(f, program = ninth, status = 413)
            assertFalse(over.containsKey("results")); assertFalse(over.containsKey("source"))
            assertEquals(3, rows(query(f)).size, "Refusal must not damage the unchanged retained source")
            assertEquals(0, f.extractionCalls.get())
        } finally { close(f) }
    }
}
