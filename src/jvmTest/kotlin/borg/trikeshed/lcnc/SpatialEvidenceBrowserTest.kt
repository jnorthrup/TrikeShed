package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual repeated guest invocations, retained by the module; browser inspection must stay read-only. */
class SpatialEvidenceBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun selectedSpatialNodeKeepsOriginalInvocationIndexesAndFrozenIdentity() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SystemOperations.default.getenv("TRIKESHED_SPATIAL_EVIDENCE_UI") == "1")
        val binding = ccekReactorBinding(coroutineContext)
        val host = HypervisorVmHost()
        val inspections = CopyOnWriteArrayList<Map<*, *>>()
        val mutations = CopyOnWriteArrayList<String>()
        var run: Map<String, Any?> = emptyMap()
        var entries: List<*> = emptyList<Any>()
        var boardSequence = 0L
        var finalBoardSequence: Long? = null
        lateinit var readBoardSequence: suspend () -> Long
        var retained = emptyMap<String, ByteArray>()
        val program = LcncProgram("spatial-evidence", s_[
            LcncNode("records", "json.value", mapOf("value" to "[7,9]"), x = 20.0, y = 20.0),
            LcncNode("loop", "scope", mapOf("item" to "record"), x = 340.0, y = 20.0, children = s_[
                LcncNode("record", "scope.in", mapOf("name" to "record")),
                LcncNode("function", "vm.polyglot", mapOf("language" to "js", "source" to "return input + 1;")),
                LcncNode("yield", "scope.out", mapOf("name" to "value", "kind" to "json"))]),
        ], s_[
            LcncWire("records", "value", "loop", "each"),
            LcncWire("record", "value", "function", "input"),
            LcncWire("function", "value", "yield", "value"),
        ])
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            context.lcncRunners["vm.polyglot"] = SubVmLegos.polyglot(host)
            retainedSimulationBrowser(context, null, binding, program,
                executorThreads = 4,
                opened = { bound ->
                    val result = bound.routes.match("/api/lcnc/run")!!.route("POST", "/api/lcnc/run",
                        jsonOf(mapOf("name" to program.name, "document" to reify(LcncProgramConfix.toJson(program)))), null)!!
                    assertEquals(200, result.status, result.body)
                    run = reifyMap(result.body)
                    assertEquals("completed", run["status"], result.body)
                    val ledger = reifyMap(assertNotNull(context.casStore.get(ContentId(run["invocationsCid"] as String))))
                    entries = assertIs<List<*>>(ledger["entries"])
                    val guests = entries.filter { (it as Map<*, *>)["nodeId"] == "function" }
                    assertEquals(2, guests.size)
                    assertEquals(listOf(8L, 10L), guests.map {
                        ((it as Map<*, *>)["outputs"] as Map<*, *>)["value"].let { n -> (n as Number).toLong() }
                    })
                    assertTrue(guests.all { (it as Map<*, *>)["runtime"] is Map<*, *> })
                    assertTrue(host.ids().none { host.get(it)?.isAlive == true })
                    readBoardSequence = {
                        val board = bound.routes.match("/api/board")!!.route("GET", "/api/board", "", null)!!
                        assertEquals(200, board.status, board.body)
                        (reifyMap(board.body)["sequence"] as Number).toLong()
                    }
                    boardSequence = readBoardSequence()
                    retained = listOf("programCid", "receiptCid", "invocationsCid").associate { key ->
                        val cid = run[key] as String
                        cid to assertNotNull(context.casStore.get(ContentId(cid))).copyOf()
                    }
                    println("SPATIAL_EVIDENCE_RUN=" + jsonOf(run.filterKeys { it in setOf("runId", "receiptCid", "programCid", "invocationsCid") }))
                },
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/workspace") inspections += reifyMap(body)
                    else if (method !in setOf("GET", "HEAD") && path != "/api/lcnc/rdf/align") mutations += "$method $path"
                },
                testRoute = { method, path, _ ->
                    if (method == "GET" && path == "/_test/finish") finalBoardSequence = readBoardSequence()
                    null
                },
                observations = { mapOf("run" to run.filterKeys { it in setOf("runId", "receiptCid", "programCid", "invocationsCid") },
                    "entries" to entries, "inspections" to inspections.toList(), "mutations" to mutations.toList(),
                    "boardSequenceBefore" to boardSequence) })
            assertTrue(mutations.isEmpty(), "Inspection must not execute, save or publish: $mutations")
            val expected = entries.withIndex().filter { (it.value as Map<*, *>)["nodeId"] == "function" }.map { it.index }
            val observed = inspections.filter { (it["source"] as? Map<*, *>)?.get("cid") == run["invocationsCid"] }
                .mapNotNull { request -> (request["path"] as? List<*>)?.takeIf { it.size == 2 && it[0] == "entries" }
                    ?.get(1)?.let { (it as Number).toInt() } }.distinct()
            assertEquals(expected, observed, "Both filtered occurrences must inspect their original immutable ledger positions")
            assertTrue(inspections.any { request ->
                request["compareProgramCid"] == run["programCid"] &&
                    LcncProgramConfix.fromJson("inspection", jsonOf(request["document"])).nodes.view
                        .flatMap { it.children.view }.any { it.id == "function" && it.params["source"] == "return input + 99;" }
            },
                "Edited canvas must be compared against the frozen run without replacing it")
            assertEquals(boardSequence, assertNotNull(finalBoardSequence),
                "Inspection and canvas edits must not append board history")
            for ((cid, bytes) in retained) assertContentEquals(bytes, context.casStore.get(ContentId(cid)))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true }, "Inspection cannot create a new guest")
            println("SPATIAL_EVIDENCE_PROOF=" + jsonOf(mapOf("invocationIndexes" to observed,
                "boardSequenceBefore" to boardSequence, "boardSequenceAfter" to finalBoardSequence,
                "mutations" to mutations.toList(), "run" to run.filterKeys { it in setOf("runId", "receiptCid", "programCid", "invocationsCid") })))
        } finally {
            withContext(NonCancellable) { try { binding.reactor.drain() } finally { host.close() } }
        }
    }
}
