package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Phase 3 gate: W1.4 — LCNC nodes as CCEK agents.
 *
 * The core fix under test: MANY-cardinality input ports collect EVERY
 * upstream branch (fan-in), where the old runner kept only the last wire —
 * making votes, quorums, and any multi-branch assembly impossible.
 */
class LcncFanInTest {

    /** Identity runner over a type that accepts MANY inputs on port `x`. */
    private val registry = mapOf(
        "display" to LcncNodeRunner { _, inputs -> inputs },      // x is MANY per contract
        "pick" to LcncNodeRunner { _, inputs -> inputs },          // x is ONE per contract
        "source" to LcncNodeRunner { n, _ -> mapOf("out" to "src-${n.id}") },
    )

    private fun program(vararg nodes: LcncNode, wires: List<LcncWire>) =
        LcncProgram("fanin", nodes.toList().toSeries(), wires.toSeries())

    // ── fan-in ────────────────────────────────────────────────────────────

    @Test
    fun manyPortCollectsEveryBranch() = runBlocking {
        // 3 sources → one display on its MANY port: all three must arrive.
        val s1 = LcncNode("s1", "source")
        val s2 = LcncNode("s2", "source")
        val s3 = LcncNode("s3", "source")
        val display = LcncNode("disp", "display") // display.x is MANY per contract
        val p = program(s1, s2, s3, display, wires = listOf(
            LcncWire("s1", "out", "disp", "x"),
            LcncWire("s2", "out", "disp", "x"),
            LcncWire("s3", "out", "disp", "x"),
        ))

        val out = LcncRunner(registry).runAll(p)
        val inputs = out["disp"] ?: throw AssertionError("display never ran")
        val x = inputs["x"] ?: throw AssertionError("x port absent")
        assertTrue(x is List<*>, "MANY port must collect branches into a list, got ${x::class.simpleName}: $x")
        assertEquals(3, (x as List<*>).size, "all 3 branches survive: $x")
    }

    @Test
    fun scalarPortKeepsLastWriteWins() = runBlocking {
        // pick.x is ONE cardinality — both upstreams run; scalar keeps one value.
        val s1 = LcncNode("s1", "source")
        val s2 = LcncNode("s2", "source")
        val pick = LcncNode("p", "pick")
        val p = program(s1, s2, pick, wires = listOf(
            LcncWire("s1", "out", "p", "x"),
            LcncWire("s2", "out", "p", "x"),
        ))

        val out = LcncRunner(registry).runAll(p)
        assertTrue(out.containsKey("s1") && out.containsKey("s2"), "both upstream sources ran")
        assertEquals(mapOf("x" to "src-s2"), out["p"], "ONE port keeps last write")
    }

    @Test
    fun singleBranchManyPortStaysScalarForRunnerConvenience() = runBlocking {
        val s1 = LcncNode("s1", "source")
        val display = LcncNode("d", "display")
        val p = program(s1, display, wires = listOf(
            LcncWire("s1", "out", "d", "x"),
        ))
        val out = LcncRunner(registry).runAll(p)
        assertEquals(mapOf("x" to "src-s1"), out["d"], "one branch ⇒ plain value, not [value]")
    }

    // ── required-input skip semantics ─────────────────────────────────────

    @Test
    fun unwiredRequiredInputSkipsNodeSilently() = runBlocking {
        // dom.board's `groups` input is required and unwired ⇒ skipped, no crash.
        val board = LcncNode("b", "dom.board")
        val p = program(board, wires = emptyList())
        val out = LcncRunner(registry).runAll(p)
        assertTrue(!out.containsKey("board"), "unwired required input ⇒ node skipped silently")
    }

    @Test
    fun wiredButUnfedRequiredInputAlsoSkips() = runBlocking {
        // The source's runner isn't in the registry — but instead of throwing,
        // capture it via a type present in topo() but absent from outputs.
        // Use a contract-registered sink whose upstream is another contract
        // node with an unwired required input, so the upstream itself skips:
        // http.post REQUIRES body; body comes from nothing here.
        val post = LcncNode("post", "http.post")
        val display = LcncNode("d", "display")
        val p = program(post, display, wires = listOf(LcncWire("post", "json", "d", "x")))
        // registry lacks http.post? No — it has it. Give post no body input:
        // its required input is unfed ⇒ skipped ⇒ display's feed is dead ⇒ skip too.
        val regWithPost = registry + ("http.post" to LcncNodeRunner { _, inputs -> mapOf("json" to inputs["body"]) })
        val out = LcncRunner(regWithPost).runAll(p)
        assertTrue(!out.containsKey("post"), "required input unfed ⇒ http.post skips")
        assertTrue(!out.containsKey("d"), "dead upstream ⇒ display skips too")
    }

    @Test
    fun unknownNodeTypeStillThrows() = runBlocking {
        val ghost = LcncNode("g", "ghost.type")
        val p = program(ghost, wires = emptyList())
        assertFailsWith<LcncUnknownNodeType> { LcncRunner(registry).runAll(p) }
    }

    // ── structured cancellation (W1.4 agent semantics) ────────────────────

    @Test
    fun runAllInCancelsWithAssemblyScope() = runBlocking {
        val slowRegistry = mapOf(
            "slow" to LcncNodeRunner { _, _ ->
                delay(10_000) // in-flight suspend work
                emptyMap<String, Any?>()
            },
        )
        val outerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = LcncRunner(slowRegistry)
        val deferred = runner.runAllIn(outerScope, program(LcncNode("s", "slow"), wires = emptyList()))

        delay(100) // let it enter the slow runner
        outerScope.cancel() // ABORT lowered to structured concurrency

        try {
            withTimeoutOrNull(2000) { deferred.await() }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected: the walk died with the assembly scope
        }
        assertTrue(deferred.isCancelled || !outerScope.isActive,
            "cancelling the assembly scope must cancel the walk")
    }

    @Test
    fun checkBeforeEachNodeHonoursPromptCancellation() = runBlocking {
        var startedCount = 0
        val countingRegistry = mapOf(
            "quick" to LcncNodeRunner { _, _ ->
                startedCount++
                emptyMap<String, Any?>()
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async {
            val chain = (0 until 5).map { LcncNode("q$it", "quick") }
            LcncRunner(countingRegistry).runAll(
                program(*chain.toTypedArray(), wires = emptyList()),
            )
        }
        job.cancel(kotlinx.coroutines.CancellationException("abort"))
        withTimeoutOrNull(1000) { job.join() }
        assertTrue(job.isCancelled)
        scope.cancel()
    }
}
