package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.forge.server.DocumentEnglishWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Real installed Camel in an owned child; the parser and class-directory deployment are explicit test fixtures. */
class CamelCorpusRoutingExecutionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private class Fixture(val ctx: ModuleContext, val cas: FileCasStore, val corpus: InMemoryProjectCorpus,
        val wire: DocumentEnglishWire, val owner: CompletableJob, val camel: CamelRouteElement, val inspection: String)

    private suspend fun fixture(name: String): Fixture {
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(currentCoroutineContext()[Job]); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus(); val text = "Audit costs remain due."
        cas.put(text.encodeToByteArray()); corpus.put("legal", "source.txt", text.encodeToByteArray())
        val wire = DocumentEnglishWire(ctx, corpus) { NlpReader { source ->
            val words = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(source).toList()
            val tokens = words.mapIndexed { i, word -> NlpToken(i + 1, word.range.first, word.range.last + 1,
                word.value, word.value.lowercase(), "NN", "O") }.toSeries()
            NlpDocument(source, s_[NlpSentence(0, 0, source.length, tokens, s_[NlpDependency(0, 1, "root")])],
                NlpMetadata("fixture", "CamelCorpusRoutingExecutionTest"))
        } }
        wire.attach(); val camel = CamelRouteLegos.register(ctx)
        try {
            val row = assertNotNull(corpus.document("legal", "source.txt"))
            val reply = assertNotNull(ctx.routes.match(DocumentEnglishWire.PATH)).route("POST", DocumentEnglishWire.PATH,
                jsonOf(_m["project" j row.project, "id" j row.id, "cid" j row.cid, "rev" j row.rev,
                    "seq" j row.seq, "begin" j 0, "end" j text.length]), null)!!
            assertEquals(200, reply.status, reply.body)
            val process = CamelCorpusProcess.testing(files, CamelCorpusRoutingExecutionTest::class.java)
            ctx.lcncRunners[CamelCorpusRouting.TYPE] = CamelCorpusRoutingRunner.runner(ctx, camel) { process }
            return Fixture(ctx, cas, corpus, wire, owner, camel, (reifyStrict(reply.body) as Map<*, *>)["receiptCid"] as String)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { wire.drain(); camel.drain(); owner.cancelAndJoin() }; throw failure
        }
    }

    private suspend fun Fixture.close() = withContext(NonCancellable) { wire.drain(); camel.drain(); owner.cancelAndJoin() }
    private suspend fun report(f: Fixture, term: String?, mode: String = "current", topics: Any? = null): Map<String, Any?> =
        f.ctx.lcncRunners.getValue(EnglishCorpusContract.TYPE).run(LcncNode("corpus", EnglishCorpusContract.TYPE),
            _m["receiptCids" j _l[f.inspection], "mode" j mode, "topics" j topics] +
                if (term == null) emptyMap() else _m["query" j _m["terms" j _l[term], "limit" j 8]])
    private suspend fun route(f: Fixture, report: Map<String, Any?>, mode: String = "current") =
        f.ctx.lcncRunners.getValue(CamelCorpusRouting.TYPE).run(LcncNode("route", CamelCorpusRouting.TYPE),
            _m["receiptCid" j report["receiptCid"], "mode" j mode])
    private fun terminal(f: Fixture): Map<String, Any?> {
        val latest = f.ctx.blackboard.snapshot().values.getValue(CamelCorpusRoutingRunner.LATEST) as Map<*, *>
        val cid = ContentId(latest["terminalCid"] as String)
        return CanonicalCbor.decodeMapBounded(assertNotNull(f.cas.getBounded(cid, 512 * 1024)), 512 * 1024)
    }

    @Test fun actualChoiceAndOfferedWalkerPreserveCorpusMatrixTopicsAndSourceWitnesses(): Unit = runBlocking {
        val f = fixture("choice")
        try {
            val config = _m["topics" j 2, "iterations" j 3, "seed" j 7, "alpha" j .1, "beta" j .01]
            for ((term, expected) in s_["audit" j "inspect-source-sentences", "absent" j "no-lexical-match"].view) {
                val corpus = report(f, term, topics = if (term == "audit") config else null)
                val original = assertNotNull(f.cas.getBounded(ContentId(corpus["receiptCid"] as String), 4 * 1024 * 1024))
                val registry = PureNodes.registry { 123L } + f.ctx.lcncRunners + TribunalNodes.registry(
                    TribunalDialog { _, _, _ -> error("Local corpus routing must not call a model") }).filterKeys { it == "display" }
                val offered = CamelCorpusRouting.build(corpus["receiptCid"] as String, "current")
                // Actual LCNC fan-out of a real execution record exercises the existing large-output boundary.
                // The empty branch below runs the offered construction unchanged.
                val nodes = offered.nodes.view.toMutableList(); val wires = offered.wires.view.toMutableList()
                if (term == "audit") for (i in 0 until 16) {
                    val id = "execution-$i"
                    nodes.add(LcncNode(id, LcncContracts.SCOPE_OUT, _m["name" j id, "kind" j "json"]))
                    wires.add(LcncWire("route", "execution", id, "value"))
                }
                val program = if (term == "audit") offered.copy(nodes = nodes.toSeries(), wires = wires.toSeries()) else offered
                val result = LcncRunner(registry).runProcedure(program)
                val output = result.nodeOutputs.getValue("route")
                assertEquals(expected, output["lane"]); assertEquals(expected, result.returns["lane"])
                for (key in s_["receiptCid", "corpusCid", "matrixCid", "topicCid", "source"].view) assertEquals(corpus[key], output[key])
                assertContentEquals(original, f.cas.getBounded(ContentId(corpus["receiptCid"] as String), 4 * 1024 * 1024))
                val end = terminal(f); assertEquals("completed", end["outcome"]); assertEquals("current-source-rechecked", end["postCheck"])
                assertEquals(output["terminalCid"], result.returns["terminalCid"])
                val execution = end["execution"] as Map<*, *>
                assertEquals(true, execution["joined"]); assertEquals(false, execution["timedOut"]); assertEquals(0L, (execution["exit"] as Number).toLong())
                assertEquals("test-class-directory-closure", (execution["profile"] as Map<*, *>)["kind"])
                val frame = (execution["output"] as String).lineSequence().single { it.startsWith(CamelCorpusProcess.PREFIX) }
                val reply = DocumentResult.parse(frame.removePrefix(CamelCorpusProcess.PREFIX))["reply"] as Map<*, *>
                assertEquals(expected, (reply["headers"] as Map<*, *>)[CamelCorpusRouting.LANE])
                val retainedOutput = LcncOutputReceipt.retain(f.cas, _m["outputs" j result.nodeOutputs, "returns" j result.returns])
                if (term == "audit") {
                    assertEquals(true, retainedOutput["outputPreview"])
                    val outputCid = ContentId(retainedOutput["outputCid"] as String)
                    assertEquals(outputCid, ContentId.of(assertNotNull(f.cas.getBounded(outputCid, 16 * 1024 * 1024))))
                } else if ("outputCid" !in retainedOutput) assertEquals(result.returns, retainedOutput["returns"])
            }
        } finally { f.close() }
    }

    @Test fun staleUnsupportedForgedAndNullRequestsRefuseBeforeChildButSnapshotRemainsExplicit(): Unit = runBlocking {
        fun jar(manifest: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zip.write(manifest); zip.closeEntry()
            }
        }.toByteArray()
        val large = buildString {
            append("Manifest-Version: 1.0\r\n")
            repeat(18_000) { append("X-Digest-$it: ${"a".repeat(50)}\r\n") }
        }
        assertTrue(large.encodeToByteArray().size > 1024 * 1024)
        CamelCorpusProcess.checkJarManifest(jar((large + "\r\n").encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            CamelCorpusProcess.checkJarManifest(jar((large + "Class-Path: unpinned.jar\r\n\r\n").encodeToByteArray()))
        }
        assertFails { CamelCorpusProcess.checkJarManifest(jar(large.encodeToByteArray() + _a[255.toByte()])) }
        assertFailsWith<IllegalArgumentException> { CamelCorpusProcess.checkJarManifest(jar(ByteArray(CamelCorpusProcess.MANIFEST_LIMIT + 1) { 32 })) }
        val f = fixture("refusals")
        try {
            assertFailsWith<IllegalArgumentException> { CamelCorpusProcess.packaged(JvmFileOperations()) }
            val current = report(f, "audit"); val plain = report(f, null)
            val node = f.ctx.lcncRunners.getValue(CamelCorpusRouting.TYPE)
            for (inputs in s_[
                _m["receiptCid" j current["receiptCid"], "mode" j null],
                _m["receiptCid" j null, "mode" j "current"],
                _m["receiptCid" j current["receiptCid"], "mode" j "snapshot"],
                _m["receiptCid" j current["receiptCid"], "mode" j "current", "endpoint" j "https://example.invalid"],
                _m["receiptCid" j "sha256:${"a".repeat(64)}", "mode" j "current"],
            ].view) assertFails { node.run(LcncNode("refuse", CamelCorpusRouting.TYPE), inputs) }
            assertFails { route(f, plain) }
            val bytes = f.cas.getBounded(ContentId(current["receiptCid"] as String), 4 * 1024 * 1024)!!
            val value = CanonicalCbor.decodeMapBounded(bytes, 4 * 1024 * 1024)
            val forged = value + _m["search" j ((value["search"] as Map<*, *>) + _m["query" j _m["terms" j _l["absent"], "limit" j 8]])]
            val forgedCid = f.cas.put(CanonicalCbor.encodeMap(forged))
            assertFails { route(f, _m["receiptCid" j forgedCid.value]) }
            f.cas.put("Amended.".encodeToByteArray()); f.corpus.put("legal", "source.txt", "Amended.".encodeToByteArray())
            assertFails { route(f, current) }
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty(), "No failed precheck may admit a child")
            val historical = report(f, "audit", "snapshot")
            val carried = route(f, historical, "snapshot")
            assertEquals(true, (carried["source"] as Map<*, *>)["historical"])
            assertEquals("explicit-historical-snapshot-rechecked", terminal(f)["postCheck"])
            for (worker in s_[InvalidUtf8CamelCorpusWorker::class.java, OverflowCamelCorpusWorker::class.java].view) {
                val child = CamelCorpusProcess.testing(JvmFileOperations(), CamelCorpusRoutingExecutionTest::class.java, worker)
                f.ctx.lcncRunners[CamelCorpusRouting.TYPE] = CamelCorpusRoutingRunner.runner(f.ctx, f.camel) { child }
                assertFailsWith<IllegalStateException> { route(f, historical, "snapshot") }
                val end = terminal(f); assertEquals("refused", end["outcome"])
                val execution = end["execution"] as Map<*, *>
                assertEquals(true, execution["joined"])
                val pid = (execution["processId"] as Number).toLong()
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
                if (worker == InvalidUtf8CamelCorpusWorker::class.java) {
                    assertEquals(false, execution["outputUtf8Valid"])
                    assertNull(execution["output"]); assertNotNull(execution["outputBase64"])
                    assertEquals(0L, (execution["exit"] as Number).toLong())
                } else {
                    assertEquals("refused-before-complete-capture", execution["capture"])
                    assertEquals("borg.trikeshed.userspace.nio.process.ProcessOutputLimitExceeded", execution["error"])
                }
            }
        } finally { f.close() }
    }

    @Test fun amendmentAfterPrecheckRefusesAfterActualCamelAndRetainsDistinctTerminal(): Unit = runBlocking {
        val f = fixture("post-currentness")
        try {
            val selected = report(f, "audit")
            val original = f.ctx.lcncRunners.getValue(EnglishCorpusContract.TYPE)
            var calls = 0
            f.ctx.lcncRunners[EnglishCorpusContract.TYPE] = LcncNodeRunner { node, inputs ->
                calls++
                original.execute(node, inputs).also {
                    if (calls == 1) {
                        val bytes = "Changed after the checked build.".encodeToByteArray()
                        f.cas.put(bytes); f.corpus.put("legal", "source.txt", bytes)
                    }
                }
            }
            val refused = assertFailsWith<IllegalStateException> { route(f, selected) }
            assertTrue(refused.message!!.contains("terminal receipt")); assertEquals(2, calls)
            val end = terminal(f)
            assertEquals("refused", end["outcome"]); assertEquals("source-check-refused", end["postCheck"])
            val execution = end["execution"] as Map<*, *>
            assertEquals(true, execution["joined"]); assertEquals(false, execution["timedOut"])
            assertTrue((execution["output"] as String).contains(CamelCorpusProcess.PREFIX))
        } finally { f.close() }
    }

    @Test fun cancellingAdmittedWaiterStillDrainsActualStartedCamelChildAndRetainsDeadlineFailure(): Unit = runBlocking {
        val f = fixture("cancel-drain")
        try {
            val corpus = report(f, "audit")
            val blocked = CamelCorpusProcess.testing(JvmFileOperations(), CamelCorpusRoutingExecutionTest::class.java, BlockedCamelCorpusWorker::class.java)
            f.ctx.lcncRunners[CamelCorpusRouting.TYPE] = CamelCorpusRoutingRunner.runner(f.ctx, f.camel) { blocked }
            val waiter = launch { route(f, corpus) }
            withTimeout(15_000) {
                while ((f.ctx.blackboard.snapshot().values[CamelCorpusRoutingRunner.LATEST] as? Map<*, *>)?.get("outcome") != "admitted") delay(5)
            }
            waiter.cancelAndJoin()
            withTimeout(30_000) { f.camel.drain() }
            val end = terminal(f)
            assertEquals("refused", end["outcome"]); assertEquals("not-completed", end["postCheck"])
            val execution = end["execution"] as Map<*, *>
            assertEquals(true, execution["timedOut"]); assertEquals(true, execution["joined"])
            assertTrue((execution["output"] as String).contains("CAMEL_TEST_ENTERED:Started"), "Actual Camel must start before the deadline fixture blocks")
            val pid = (execution["processId"] as Number).toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "The exact child must be reaped, not just its waiter cancelled")
        } finally { f.close() }
    }
}

/** Test code only, pinned by the internal class-directory factory. No production fault option. */
object BlockedCamelCorpusWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty() && System.getenv("TRIKESHED_AIRGAP") == "1")
        val route = CamelRuntime.start("deadline-fixture", "direct:deadline-fixture", "mock:deadline-fixture")
        println("CAMEL_TEST_ENTERED:${route.status()}"); System.out.flush()
        while (true) Thread.sleep(1000)
    }
}

/** A valid real-Camel reply followed by malformed bytes must not be repaired by replacement decoding. */
object InvalidUtf8CamelCorpusWorker {
    @JvmStatic fun main(args: Array<String>) {
        CamelCorpusWorker.main(args)
        System.out.write(255); System.out.flush()
    }
}

/** A valid early reply is not success when the same owned child then exceeds its capture budget. */
object OverflowCamelCorpusWorker {
    @JvmStatic fun main(args: Array<String>) {
        CamelCorpusWorker.main(args)
        System.out.write(ByteArray(CamelCorpusProcess.OUTPUT_LIMIT) { 120 }); System.out.flush()
    }
}
